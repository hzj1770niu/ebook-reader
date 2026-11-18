package com.zz.reader.pro.core;

import java.io.File;
import java.io.IOException;

public interface BookParser {
    /**
     * @param file            已经复制到本地的电子书文件
     * @param forcedEncoding  用户手动指定的编码；null 或"自动检测"表示自动识别
     * @param images          图片仓库，用于提取封面和插图
     * @param mediaDir        这本书的图片目录
     */
    Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException;
}
