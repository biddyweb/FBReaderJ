/*
 * Copyright (C) 2007-2014 Geometer Plus <contact@geometerplus.com>
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

package org.geometerplus.fbreader.formats;

import org.geometerplus.zlibrary.core.drm.EncryptionMethod;
import org.geometerplus.zlibrary.core.filesystem.*;
import org.geometerplus.zlibrary.core.image.ZLImage;

import org.geometerplus.fbreader.book.Book;
import org.geometerplus.fbreader.book.BookUtil;
import org.geometerplus.fbreader.bookmodel.BookReadingException;

public class PdfPluginFormatPlugin extends PluginFormatPlugin {
	private final String PACKAGE = "org.geometerplus.fbreader.plugin.pdf";
	
	public PdfPluginFormatPlugin() {
		super("PDF");
	}

	@Override
	public String getPackage() {
		return PACKAGE;
	}

	@Override
	public void readMetaInfo(Book book) throws BookReadingException {
		//TODO
	}

	@Override
	public String readEncryptionMethod(Book book) {
		return EncryptionMethod.NONE;
	}

	@Override
	public String readAnnotation(ZLFile file) {
		//TODO
		return null;
	}
	
	@Override
	public void readUids(Book book) throws BookReadingException {
		if (book.uids().isEmpty()) {
			book.addUid(BookUtil.createSHA256Uid(book.File));
		}
	}
}
