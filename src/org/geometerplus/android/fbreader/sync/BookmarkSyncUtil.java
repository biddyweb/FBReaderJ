/*
 * Copyright (C) 2010-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.sync;

import java.util.*;

import org.geometerplus.zlibrary.core.network.JsonRequest2;

import org.geometerplus.fbreader.book.*;
import org.geometerplus.fbreader.fbreader.options.SyncOptions;

class BookmarkSyncUtil {
	static void sync(SyncNetworkContext context, final IBookCollection collection) {
		try {
			final Map<String,Info> actualServerInfos = new HashMap<String,Info>();
			final Set<String> deletedOnServerUids = new HashSet<String>();
			JsonRequest2 infoRequest = null;

			// Step 0: loading bookmarks info lists (actual & deleted bookmark ids)
			final Map<String,Object> data = new HashMap<String,Object>();
			final int pageSize = 100;
			data.put("page_size", pageSize);
			final Map<String,Object> responseMap = new HashMap<String,Object>();

			for (int pageNo = 0; ; ++pageNo) {
				data.put("page_no", pageNo);
				data.put("timestamp", System.currentTimeMillis());
				infoRequest = new JsonRequest2(
					SyncOptions.BASE_URL + "sync/bookmarks.lite.paged", data
				) {
					@Override
					public void processResponse(Object response) {
						System.err.println("BMK RESPONSE = " + response);
						responseMap.putAll((Map<String,Object>)response);
					}
				};
				context.perform(infoRequest);
				for (Map<String,Object> info : (List<Map<String,Object>>)responseMap.get("actual")) {
					final Info bmk = new Info(info);
					actualServerInfos.put(bmk.Uid, bmk);
				}
				deletedOnServerUids.addAll((List<String>)responseMap.get("deleted"));
				if ((Long)responseMap.get("count") <= (pageNo + 1L) * pageSize) {
					break;
				}
			}

			System.err.println("BMK ACTUAL = " + actualServerInfos);
			System.err.println("BMK DELETED = " + deletedOnServerUids);

			// Step 1: purge deleted bookmarks info already synced with server
			final Set<String> deletedOnClientUids = new HashSet<String>(
				collection.deletedBookmarkUids()
			);
			if (!deletedOnClientUids.isEmpty()) {
				final List<String> toPurge = new ArrayList<String>(deletedOnClientUids);
				toPurge.removeAll(actualServerInfos.keySet());
				if (!toPurge.isEmpty()) {
					collection.purgeBookmarks(toPurge);
				}
			}

			// Step 2: prepare lists of bookmarks to create/delete/update on server/client
			//    (total 6 lists)
			final List<Bookmark> toSendToServer = new LinkedList<Bookmark>();
			final List<Bookmark> toDeleteOnClient = new LinkedList<Bookmark>();
			final List<Bookmark> toUpdateOnServer = new LinkedList<Bookmark>();
			final List<Bookmark> toUpdateOnClient = new LinkedList<Bookmark>();
			final List<String> toGetFromServer = new LinkedList<String>();
			final List<String> toDeleteOnServer = new LinkedList<String>();

			for (BookmarkQuery q = new BookmarkQuery(20); ; q = q.next()) {
				final List<Bookmark> bmks = collection.bookmarks(q);
				if (bmks.isEmpty()) {
					break;
				}
				for (Bookmark b : bmks) {
					final Info info = actualServerInfos.remove(b.Uid);
					if (info != null) {
						if (info.VersionUid == null) {
							if (b.getVersionUid() != null) {
								toUpdateOnServer.add(b);
							}
						} else {
							if (b.getVersionUid() == null) {
								toUpdateOnClient.add(b);
							} else if (!info.VersionUid.equals(b.getVersionUid())) {
								final long ts = b.getDate(Bookmark.DateType.Modification).getTime();
								if (info.ModificationTimestamp <= ts) {
									toUpdateOnServer.add(b);
								} else {
									toUpdateOnClient.add(b);
								}
							}
						}
					} else if (deletedOnServerUids.contains(b.Uid)) {
						toDeleteOnClient.add(b);
					} else {
						toSendToServer.add(b);
					}
				}
			}

			final Set<String> leftUids = actualServerInfos.keySet();
			if (!leftUids.isEmpty()) {
				toGetFromServer.addAll(leftUids);
				toGetFromServer.removeAll(deletedOnClientUids);

				toDeleteOnServer.addAll(leftUids);
				toDeleteOnServer.retainAll(deletedOnClientUids);
			}

			// collecting book hashes & removing bookmarks with unknown book hash
			final BooksByHash booksByHash = new BooksByHash(collection);
			for (ListIterator<String> iter = toGetFromServer.listIterator(); iter.hasNext(); ) {
				final Info info = actualServerInfos.get(iter.next());
				if (booksByHash.getBook(info.BookHashes) == null) {
					iter.remove();
				}
			}

			System.err.println("BMK TO SEND TO SERVER = " + ids(toSendToServer));
			System.err.println("BMK TO DELETE ON SERVER = " + toDeleteOnServer);
			System.err.println("BMK TO DELETE ON CLIENT = " + ids(toDeleteOnClient));
			System.err.println("BMK TO UPDATE ON SERVER = " + ids(toUpdateOnServer));
			System.err.println("BMK TO UPDATE ON CLIENT = " + ids(toUpdateOnClient));
			System.err.println("BMK TO GET FROM SERVER = " + toGetFromServer);

			// Step 3a: deleting obsolete bookmarks on client
			for (Bookmark b : toDeleteOnClient) {
				collection.deleteBookmark(b);
			}

			// Step 3b: getting new bookmarks from the server,
			//    creating new objects on the client side
			context.perform(new JsonRequest2(
				SyncOptions.BASE_URL + "sync/bookmarks", toGetFromServer
			) {
				@Override
				public void processResponse(Object response) {
					for (Map<String,Object> info : (List<Map<String,Object>>)response) {
						final Bookmark bookmark = bookmarkFromData(info, booksByHash);
						if (bookmark != null) {
							collection.saveBookmark(bookmark);
						}
					}
				}
			});

			// Step 3c: getting updated bookmarks from the server,
			//    updating objects on the client side
			final Map<String,Bookmark> bookmarksMap = new HashMap<String,Bookmark>();
			for (Bookmark b : toUpdateOnClient) {
				bookmarksMap.put(b.Uid, b);
			}
			context.perform(new JsonRequest2(
				SyncOptions.BASE_URL + "sync/bookmarks", ids(toUpdateOnClient)
			) {
				@Override
				public void processResponse(Object response) {
					for (Map<String,Object> info : (List<Map<String,Object>>)response) {
						final Bookmark bookmark = bookmarkToUpdate(info, bookmarksMap);
						if (bookmark != null) {
							collection.saveBookmark(bookmark);
						}
					}
				}
			});

			// Step 3d: sending locally updated information to the server
			class HashCache {
				final Map<Long,String> myHashByBookId = new HashMap<Long,String>();

				String getHash(Bookmark b) {
					String hash = myHashByBookId.get(b.BookId);
					if (hash == null) {
						final Book book = collection.getBookById(b.BookId);
						hash = book != null ? collection.getHash(book, false) : "";
						myHashByBookId.put(b.BookId, hash);
					}
					return "".equals(hash) ? null : hash;
				}
			};

			final HashCache cache = new HashCache();

			final List<Request> requests = new ArrayList<Request>();
			for (Bookmark b : toSendToServer) {
				final String hash = cache.getHash(b);
				if (hash != null) {
					requests.add(new AddRequest(b, hash));
				}
			}
			for (Bookmark b : toUpdateOnServer) {
				final String hash = cache.getHash(b);
				if (hash != null) {
					requests.add(new UpdateRequest(b, hash));
				}
			}
			for (String uid : toDeleteOnServer) {
				requests.add(new DeleteRequest(uid));
			}
			final Map<String,Object> dataForSending = new HashMap<String,Object>();
			dataForSending.put("requests", requests);
			dataForSending.put("timestamp", System.currentTimeMillis());
			final JsonRequest2 serverUpdateRequest = new JsonRequest2(
				SyncOptions.BASE_URL + "sync/update.bookmarks", dataForSending
			) {
				@Override
				public void processResponse(Object response) {
					System.err.println("UPDATED: " + response);
				}
			};
			final String csrfToken = context.getCookieValue(SyncOptions.DOMAIN, "csrftoken");
			serverUpdateRequest.addHeader("Referer", infoRequest.getURL());
			serverUpdateRequest.addHeader("X-CSRFToken", csrfToken);
			context.perform(serverUpdateRequest);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private static final class BooksByHash extends HashMap<String,Book> {
		private final IBookCollection myCollection;

		BooksByHash(IBookCollection collection) {
			myCollection = collection;
		}

		Book getBook(List<String> hashes) {
			Book book = null;
			for (String h : hashes) {
				book = get(h);
				if (book != null) {
					break;
				}
			}
			if (book == null) {
				for (String h : hashes) {
					book = myCollection.getBookByHash(h);
					if (book != null) {
						break;
					}
				}
			}
			if (book != null) {
				for (String h : hashes) {
					put(h, book);
				}
			}
			return book;
		}

		Book getBook(String hash) {
			Book book = get(hash);
			if (book == null) {
				book = myCollection.getBookByHash(hash);
				if (book != null) {
					put(hash, book);
				}
			}
			return book;
		}
	}

	private static final class Info {
		final String Uid;
		final String VersionUid;
		final List<String> BookHashes;
		final long ModificationTimestamp;

		Info(Map<String,Object> data) {
			Uid = (String)data.get("uid");
			VersionUid = (String)data.get("version_uid");
			BookHashes = (List<String>)data.get("book_hashes");
			final Long timestamp = (Long)data.get("modification_timestamp");
			ModificationTimestamp = timestamp != null ? timestamp : 0L;
		}

		@Override
		public String toString() {
			return Uid + " (" + VersionUid + "); " + ModificationTimestamp;
		}
	}

	private static abstract class Request extends HashMap<String,Object> {
		Request(String action) {
			put("action", action);
		}
	}

	private static abstract class ChangeRequest extends Request {
		ChangeRequest(String action, Bookmark bookmark, String bookHash) {
			super(action);
			final Map<String,Object> bmk = new HashMap<String,Object>();
			bmk.put("book_hash", bookHash);
			bmk.put("uid", bookmark.Uid);
			bmk.put("version_uid", bookmark.getVersionUid());
			bmk.put("style_id", bookmark.getStyleId());
			bmk.put("text", bookmark.getText());
			bmk.put("model_id", bookmark.ModelId);
			bmk.put("para_start", bookmark.getParagraphIndex());
			bmk.put("elmt_start", bookmark.getElementIndex());
			bmk.put("char_start", bookmark.getCharIndex());
			bmk.put("para_end", bookmark.getEnd().getParagraphIndex());
			bmk.put("elmt_end", bookmark.getEnd().getElementIndex());
			bmk.put("char_end", bookmark.getEnd().getCharIndex());
			bmk.put("creation_timestamp", bookmark.getDate(Bookmark.DateType.Creation).getTime());
			final Date accessDate = bookmark.getDate(Bookmark.DateType.Access);
			if (accessDate != null) {
				bmk.put("access_timestamp", accessDate.getTime());
			}
			final Date modificationDate = bookmark.getDate(Bookmark.DateType.Modification);
			if (modificationDate != null) {
				bmk.put("modification_timestamp", modificationDate.getTime());
			}

			put("bookmark", bmk);
		}
	}

	private static class AddRequest extends ChangeRequest {
		AddRequest(Bookmark bookmark, String bookHash) {
			super("add", bookmark, bookHash);
		}
	}

	private static class UpdateRequest extends ChangeRequest {
		UpdateRequest(Bookmark bookmark, String bookHash) {
			super("update", bookmark, bookHash);
		}
	}

	private static class DeleteRequest extends Request {
		DeleteRequest(String uid) {
			super("delete");
			put("uid", uid);
		}
	}

	private static List<String> ids(List<Bookmark> bmks) {
		final List<String> uids = new ArrayList<String>(bmks.size());
		for (Bookmark b : bmks) {
			uids.add(b.Uid);
		}
		return uids;
	}

	private static Date getDate(Map<String,Object> data, String key) {
		final Long timestamp = (Long)data.get(key);
		return timestamp != null ? new Date(timestamp) : null;
	}

	private static int getInt(Map<String,Object> data, String key) {
		return (int)(long)(Long)data.get(key);
	}

	private static Bookmark bookmarkFromData(Map<String,Object> data, long bookId, String bookTitle) {
		return new Bookmark(
			-1, (String)data.get("uid"), (String)data.get("version_uid"),
			bookId, bookTitle,
			(String)data.get("text"),
			getDate(data, "creation_timestamp"),
			getDate(data, "modification_timestamp"),
			getDate(data, "access_timestamp"),
			(String)data.get("model_id"),
			getInt(data, "para_start"), getInt(data, "elmt_start"), getInt(data, "char_start"),
			getInt(data, "para_end"), getInt(data, "elmt_end"), getInt(data, "char_end"),
			true,
			getInt(data, "style_id")
		);
	}

	private static Bookmark bookmarkFromData(Map<String,Object> data, BooksByHash booksByHash) {
		final Book book = booksByHash.getBook((String)data.get("book_hash"));
		if (book == null) {
			return null;
		}
		return bookmarkFromData(data, book.getId(), book.getTitle());
	}

	private static Bookmark bookmarkToUpdate(Map<String,Object> data, Map<String,Bookmark> bookmarksMap) {
		final Bookmark oldBookmark = bookmarksMap.get((String)data.get("uid"));
		if (oldBookmark == null) {
			return null;
		}
		return bookmarkFromData(data, oldBookmark.BookId, oldBookmark.BookTitle);
	}
}
