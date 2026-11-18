package com.zz.reader.pro.core;

/** 解析失败的统一异常，message 直接面向用户播报 */
public class ParseException extends Exception {
    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
