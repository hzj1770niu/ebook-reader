package com.zz.reader.pro;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/** 只做一件事：把下载好的 APK 交给系统安装器，不需要额外的依赖库 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zz.reader.pro.update";

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Uri uriFor(Context c, File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(apk.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String name = uri.getLastPathSegment();
        File f = new File(dir(getContext()), name == null ? "" : name);
        if (!f.isFile()) throw new java.io.FileNotFoundException("没有这个安装包");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
