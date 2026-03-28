/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.gles;

import android.opengl.GLES20;

import java.nio.ByteBuffer;

/**
 * Code for generating images useful for testing textures.
 * 
 * 🖼️ 生成测试纹理的工具类
 * 💡 提供两种测试纹理：粗糙4x4网格和精细棋盘格图案
 */
public class GeneratedTexture {
    //private static final String TAG = GlUtil.TAG;

    /**
     * 测试图像类型枚举
     * 💡 COARSE：粗糙的4x4色块网格
     * 💡 FINE：精细的棋盘格图案
     */
    public enum Image { COARSE, FINE };

    // Basic colors, in little-endian RGBA.
    // 🎨 基本颜色常量（小端序RGBA格式）
    // 💡 格式：0xAABBGGRR（AA=透明度, BB=蓝, GG=绿, RR=红）
    private static final int BLACK = 0x00000000;     // ⬛ 黑色
    private static final int RED = 0x000000ff;       // 🔴 红色
    private static final int GREEN = 0x0000ff00;     // 🟢 绿色
    private static final int BLUE = 0x00ff0000;      // 🔵 蓝色
    private static final int MAGENTA = RED | BLUE;   // 🟣 品红色（红+蓝）
    private static final int YELLOW = RED | GREEN;   // 🟡 黄色（红+绿）
    private static final int CYAN = GREEN | BLUE;    // 🩵 青色（绿+蓝）
    private static final int WHITE = RED | GREEN | BLUE;  // ⬜ 白色（红+绿+蓝）
    private static final int OPAQUE = (int) 0xff000000L;  // 🔲 不透明（alpha=255）
    private static final int HALF = (int) 0x80000000L;    // 🔲 半透明（alpha=128）
    private static final int LOW = (int) 0x40000000L;     // 🔲 低透明度（alpha=64）
    private static final int TRANSP = 0;                   // 🔲 完全透明（alpha=0）

    /**
     * 🎨 4x4网格颜色数组（必须是16个元素）
     * 💡 用于generateCoarseData()生成粗糙测试纹理
     * 💡 布局（按行优先）：
     *    行0: 红色、黄色、绿色、品红
     *    行1: 白色、低透明红、低透明绿、黄色
     *    行2: 品红、透明绿、半透明红、黑色
     *    行3: 青色、品红、青色、蓝色
     */
    private static final int GRID[] = new int[] {    // must be 16 elements
        OPAQUE|RED,     OPAQUE|YELLOW,  OPAQUE|GREEN,   OPAQUE|MAGENTA,
        OPAQUE|WHITE,   LOW|RED,        LOW|GREEN,      OPAQUE|YELLOW,
        OPAQUE|MAGENTA, TRANSP|GREEN,   HALF|RED,       OPAQUE|BLACK,
        OPAQUE|CYAN,    OPAQUE|MAGENTA, OPAQUE|CYAN,    OPAQUE|BLUE,
    };

    // 📐 纹理尺寸常量
    private static final int TEX_SIZE = 64;         // must be power of 2  // 📐 纹理大小（必须是2的幂）
    private static final int FORMAT = GLES20.GL_RGBA;  // 🎨 纹理格式（RGBA）
    private static final int BYTES_PER_PIXEL = 4;   // RGBA  // 📦 每像素字节数（RGBA=4字节）

    // Generate test image data.  This must come after the other values are initialized.
    // 🖼️ 生成测试图像数据（必须在其他值初始化之后）
    // 💡 sCoarseImageData：粗糙4x4网格纹理的像素数据
    // 💡 sFineImageData：精细棋盘格纹理的像素数据
    private static final ByteBuffer sCoarseImageData = generateCoarseData();
    private static final ByteBuffer sFineImageData = generateFineData();


    /**
     * Creates a test texture in the current GL context.
     * <p>
     * This follows image conventions, so the pixel data at offset zero is intended to appear
     * in the top-left corner.  Color values for non-opaque alpha will be pre-multiplied.
     *
     * 🖼️ 在当前GL上下文中创建测试纹理
     * 💡 遵循图像惯例，偏移0的像素数据出现在左上角
     * 💡 非不透明alpha的颜色值会进行预乘
     * 
     * @return Handle to texture.  // 返回纹理句柄
     */
    public static int createTestTexture(Image which) {
        // 📦 buf：根据选择的纹理类型获取对应的像素数据
        // 💡 为什么定义：需要根据不同类型选择不同的像素数据源
        // 💡 作用：指向粗糙或精细纹理的ByteBuffer
        // 💡 使用时机：在传递给GlUtil.createImageTexture()时使用
        ByteBuffer buf;
        // 🔀 根据传入的Image枚举选择对应的像素数据
        switch (which) {
            case COARSE:
                buf = sCoarseImageData;  // 📦 使用粗糙纹理数据
                break;
            case FINE:
                buf = sFineImageData;    // 📦 使用精细纹理数据
                break;
            default:
                // ⚠️ 未知的纹理类型，抛出异常
                throw new RuntimeException("unknown image");
        }
        // 🖼️ 调用工具方法创建GL纹理，传入像素数据和纹理参数
        return GlUtil.createImageTexture(buf, TEX_SIZE, TEX_SIZE, FORMAT);
    }

    /**
     * Generates a "coarse" test image.  We want to create a 4x4 block pattern with obvious color
     * values in the corners, so that we can confirm orientation and coverage.  We also
     * leave a couple of alpha holes to check that channel.  Single pixels are set in two of
     * the corners to make it easy to see if we're cutting the texture off at the edge.
     * <p>
     * Like most image formats, the pixel data begins with the top-left corner, which is
     * upside-down relative to OpenGL conventions.  The texture coordinates should be flipped
     * vertically.  Using an asymmetric patterns lets us check that we're doing that right.
     * <p>
     * Colors use pre-multiplied alpha (so set glBlendFunc appropriately).
     *
     * 🖼️ 生成"粗糙"测试图像（4x4色块网格）
     * 💡 用途：确认纹理方向和覆盖范围
     * 💡 特点：
     *    - 4x4色块网格，角上有明显的颜色值
     *    - 留有几个alpha通道测试点（低透明度、半透明、完全透明）
     *    - 两个角上的单像素白色，用于检测纹理边缘是否被裁剪
     *    - 非对称图案，用于检测纹理坐标是否正确翻转
     * 💡 颜色使用预乘alpha（需相应设置glBlendFunc）
     *
     * @return A direct ByteBuffer with the 8888 RGBA data.  // 返回包含8888 RGBA数据的直接ByteBuffer
     */
    /**
     * 🖼️ 生成粗糙4x4网格测试纹理数据
     * 💡 用途：创建64x64像素的纹理，包含4x4色块网格
     * 💡 每个色块16x16像素，使用GRID数组中的颜色
     * 
     * @return 包含RGBA像素数据的直接ByteBuffer
     */
    private static ByteBuffer generateCoarseData() {
        // 📦 buf：像素数据缓冲区，存储64x64纹理的所有像素
        // 💡 为什么定义：用于存储生成的像素数据
        // 💡 作用：作为像素数据的临时存储，在处理完所有像素后转存到ByteBuffer
        // 💡 使用时机：在循环中逐像素填充，最后转存到ByteBuffer返回
        byte[] buf = new byte[TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL];

        // 📐 scale：缩放比例，将64x64像素映射到4x4网格
        // 💡 为什么定义：用于计算每个像素属于哪个网格块
        // 💡 作用：将纹理坐标转换为网格坐标
        // 💡 使用时机：在循环中计算gridRow和gridCol时使用
        final int scale = TEX_SIZE / 4;        // convert 64x64 --> 4x4

        // 🔄 遍历每个像素，每次步进4字节（RGBA）
        // 💡 为什么循环：处理纹理中的每个像素
        // 💡 步长BYTES_PER_PIXEL=4：因为每个像素占用4字节（R,G,B,A）
        for (int i = 0; i < buf.length; i += BYTES_PER_PIXEL) {
            // 📐 texRow：当前像素在纹理中的行号（0-63）
            // 💡 为什么定义：用于计算该像素属于哪个网格行
            // 💡 作用：将线性索引i转换为二维坐标
            // 💡 使用时机：在计算gridRow时使用
            int texRow = (i / BYTES_PER_PIXEL) / TEX_SIZE;

            // 📐 texCol：当前像素在纹理中的列号（0-63）
            // 💡 为什么定义：用于计算该像素属于哪个网格列
            // 💡 作用：将线性索引i转换为二维坐标
            // 💡 使用时机：在计算gridCol时使用
            int texCol = (i / BYTES_PER_PIXEL) % TEX_SIZE;

            // 📊 gridRow：当前像素在4x4网格中的行号（0-3）
            // 💡 为什么定义：用于确定该像素应该使用GRID数组中的哪一行颜色
            // 💡 作用：将64行纹理映射到4行网格
            // 💡 使用时机：在计算gridIndex时使用
            int gridRow = texRow / scale;  // 0-3

            // 📊 gridCol：当前像素在4x4网格中的列号（0-3）
            // 💡 为什么定义：用于确定该像素应该使用GRID数组中的哪一列颜色
            // 💡 作用：将64列纹理映射到4列网格
            // 💡 使用时机：在计算gridIndex时使用
            int gridCol = texCol / scale;  // 0-3

            // 📊 gridIndex：在GRID颜色数组中的索引（0-15）
            // 💡 为什么定义：用于从GRID数组中获取当前像素的颜色
            // 💡 作用：将二维网格坐标转换为一维数组索引
            // 💡 使用时机：在获取GRID[gridIndex]颜色时使用
            int gridIndex = (gridRow * 4) + gridCol;  // 0-15

            // 🎨 color：当前像素的颜色值（RGBA格式）
            // 💡 为什么定义：存储当前像素的初始颜色
            // 💡 作用：从GRID数组获取颜色，可能被角点覆盖
            // 💡 使用时机：在提取RGBA分量和预乘计算时使用
            int color = GRID[gridIndex];

            // override the pixels in two corners to check coverage
            // 🔲 覆盖两个角的像素为白色，用于检测纹理覆盖范围
            // 💡 为什么：验证整个纹理是否正确渲染，没有被裁剪
            if (i == 0) {
                color = OPAQUE | WHITE;  // 左上角：白色（便于视觉识别）
            } else if (i == buf.length - BYTES_PER_PIXEL) {
                color = OPAQUE | WHITE;  // 右下角：白色（便于视觉识别）
            }

            // extract RGBA; use "int" instead of "byte" to get unsigned values
            // 🔍 提取RGBA分量（使用int而非byte以获取无符号值0-255）
            // 💡 为什么用int：Java的byte是有符号的（-128到127），用int可以正确处理0-255

            // 🔴 red：红色分量（0-255）
            // 💡 为什么定义：需要单独提取用于预乘计算
            // 💡 作用：存储颜色的红色通道值
            // 💡 使用时机：在预乘计算 (red * alphaM) 时使用
            int red = color & 0xff;

            // 🟢 green：绿色分量（0-255）
            // 💡 为什么定义：需要单独提取用于预乘计算
            // 💡 作用：存储颜色的绿色通道值
            // 💡 使用时机：在预乘计算 (green * alphaM) 时使用
            int green = (color >> 8) & 0xff;

            // 🔵 blue：蓝色分量（0-255）
            // 💡 为什么定义：需要单独提取用于预乘计算
            // 💡 作用：存储颜色的蓝色通道值
            // 💡 使用时机：在预乘计算 (blue * alphaM) 时使用
            int blue = (color >> 16) & 0xff;

            // 🔲 alpha：透明度分量（0-255）
            // 💡 为什么定义：需要单独提取用于预乘计算和存储
            // 💡 作用：存储颜色的透明度值
            // 💡 使用时机：在计算alphaM和存储buf[i+3]时使用
            int alpha = (color >> 24) & 0xff;

            // pre-multiply colors and store in buffer
            // 🎨 预乘颜色并存储到缓冲区
            // 💡 预乘公式：finalColor = originalColor * (alpha / 255)

            // 🔲 alphaM：归一化的alpha值（0.0-1.0）
            // 💡 为什么定义：用于将颜色值从0-255范围转换为预乘后的值
            // 💡 作用：作为预乘系数
            // 💡 使用时机：在计算预乘颜色 (red * alphaM) 时使用
            float alphaM = alpha / 255.0f;

            // 🔴 buf[i]：预乘后的红色分量
            // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
            // 💡 作用：存储像素的R通道
            // 💡 使用时机：在创建纹理时作为像素数据的一部分
            buf[i] = (byte) (red * alphaM);

            // 🟢 buf[i+1]：预乘后的绿色分量
            // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
            // 💡 作用：存储像素的G通道
            // 💡 使用时机：在创建纹理时作为像素数据的一部分
            buf[i+1] = (byte) (green * alphaM);

            // 🔵 buf[i+2]：预乘后的蓝色分量
            // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
            // 💡 作用：存储像素的B通道
            // 💡 使用时机：在创建纹理时作为像素数据的一部分
            buf[i+2] = (byte) (blue * alphaM);

            // 🔲 buf[i+3]：原始alpha值（不预乘）
            // 💡 为什么存储：alpha通道保持原始值，不参与预乘
            // 💡 作用：存储像素的A通道
            // 💡 使用时机：在创建纹理时作为像素数据的一部分
            buf[i+3] = (byte) alpha;
        }

        // 📦 byteBuf：直接ByteBuffer，用于OpenGL纹理创建
        // 💡 为什么定义：OpenGL需要直接缓冲区来高效传输数据
        // 💡 作用：将字节数组转换为OpenGL可用的缓冲区格式
        // 💡 使用时机：在createTestTexture()中传递给GlUtil.createImageTexture()
        ByteBuffer byteBuf = ByteBuffer.allocateDirect(buf.length);
        byteBuf.put(buf);
        byteBuf.position(0);  // 🔄 重置位置到开始，准备读取
        return byteBuf;
    }

    /**
     * Generates a fine-grained test image.
     *
     * 🖼️ 生成精细纹理测试图像（棋盘格图案）
     * 💡 用途：测试纹理滤波和缩放效果
     * 💡 图案布局：
     *    - 左上：1像素红蓝棋盘格
     *    - 右下：2像素红绿棋盘格
     *    - 左下：4像素蓝绿棋盘格
     *    - 右上：8像素黑白棋盘格
     *
     * @return A direct ByteBuffer with the 8888 RGBA data.  // 返回包含8888 RGBA数据的直接ByteBuffer
     */
    private static ByteBuffer generateFineData() {
        // 📦 buf：像素数据缓冲区，存储64x64纹理的所有像素
        // 💡 为什么定义：用于临时存储精细纹理的像素数据
        // 💡 作用：每个像素4字节（RGBA），总共64*64*4=16384字节
        // 💡 使用时机：在checkerPattern填充和转存到ByteBuffer时使用
        byte[] buf = new byte[TEX_SIZE * TEX_SIZE * BYTES_PER_PIXEL];  // 📦 像素数据缓冲区

        // top/left: single-pixel red/blue
        // 🔺 左上象限：1像素红蓝棋盘格（最小粒度，用于检测纹理滤波）
        checkerPattern(buf, 0, 0, TEX_SIZE / 2, TEX_SIZE / 2,
                OPAQUE|RED, OPAQUE|BLUE, 0x01);
        // bottom/right: two-pixel red/green
        // 🔻 右下象限：2像素红绿棋盘格
        checkerPattern(buf, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE, TEX_SIZE,
                OPAQUE|RED, OPAQUE|GREEN, 0x02);
        // bottom/left: four-pixel blue/green
        // 🔻 左下象限：4像素蓝绿棋盘格
        checkerPattern(buf, 0, TEX_SIZE / 2, TEX_SIZE / 2, TEX_SIZE,
                OPAQUE|BLUE, OPAQUE|GREEN, 0x04);
        // top/right: eight-pixel black/white
        // 🔺 右上象限：8像素黑白棋盘格（最大粒度）
        checkerPattern(buf, TEX_SIZE / 2, 0, TEX_SIZE, TEX_SIZE / 2,
                OPAQUE|WHITE, OPAQUE|BLACK, 0x08);

        // 📦 byteBuf：直接ByteBuffer，用于OpenGL纹理创建
        // 💡 为什么定义：OpenGL需要直接缓冲区来高效传输数据
        // 💡 作用：将字节数组转换为OpenGL可用的缓冲区格式
        // 💡 使用时机：在createTestTexture()中传递给GlUtil.createImageTexture()
        ByteBuffer byteBuf = ByteBuffer.allocateDirect(buf.length);
        // 📝 将字节数组数据写入直接缓冲区
        byteBuf.put(buf);
        // 🔄 重置位置到0，准备读取
        byteBuf.position(0);
        // ✅ 返回填充好数据的ByteBuffer
        return byteBuf;
    }

    /**
     * 在指定区域生成棋盘格图案
     * 
     * 🖼️ 棋盘格图案生成器
     * 💡 使用位运算判断当前像素应该使用哪种颜色
     * 
     * @param buf 像素数据缓冲区
     * @param left 左边界
     * @param top 上边界
     * @param right 右边界
     * @param bottom 下边界
     * @param color1 第一种颜色
     * @param color2 第二种颜色
     * @param bit 位掩码（决定棋盘格粒度：0x01=1像素，0x02=2像素，0x04=4像素，0x08=8像素）
     */
    /**
     * 🖼️ 在指定区域生成棋盘格图案
     * 💡 使用位运算实现颜色交替，bit参数决定棋盘格粒度
     * 💡 原理：(row & bit) ^ (col & bit) 的结果决定使用color1还是color2
     * 
     * @param buf 像素数据缓冲区（输出）
     * @param left 左边界（像素坐标）
     * @param top 上边界（像素坐标）
     * @param right 右边界（像素坐标，不包含）
     * @param bottom 下边界（像素坐标，不包含）
     * @param color1 第一种颜色（RGBA格式）
     * @param color2 第二种颜色（RGBA格式）
     * @param bit 位掩码（决定棋盘格粒度：0x01=1像素，0x02=2像素，0x04=4像素，0x08=8像素）
     */
    private static void checkerPattern(byte[] buf, int left, int top, int right, int bottom,
            int color1, int color2, int bit) {
        // 🔄 遍历指定区域的每一行
        // 💡 为什么循环：处理区域内的每一行像素
        // 💡 row：当前行号（像素坐标）
        for (int row = top; row < bottom; row++) {
            // 📐 rowOffset：当前行在buf中的字节偏移
            // 💡 为什么定义：避免每列都重复计算行偏移，提高效率
            // 💡 作用：存储当前行第一个像素在buf中的位置
            // 💡 使用时机：在计算当前像素的完整偏移时使用
            int rowOffset = row * TEX_SIZE * BYTES_PER_PIXEL;

            // 🔄 遍历当前行的每一列
            // 💡 为什么循环：处理当前行中的每个像素
            // 💡 col：当前列号（像素坐标）
            for (int col = left; col < right; col++) {
                // 📐 offset：当前像素在buf中的字节偏移
                // 💡 为什么定义：用于定位当前像素在buf中的存储位置
                // 💡 作用：计算像素的完整偏移 = 行偏移 + 列偏移
                // 💡 使用时机：在写入buf[offset]到buf[offset+3]时使用
                int offset = rowOffset + col * BYTES_PER_PIXEL;

                // 🎨 color：当前像素的颜色值
                // 💡 为什么定义：根据棋盘格规则选择颜色
                // 💡 作用：存储当前像素的RGBA颜色值
                // 💡 使用时机：在提取RGBA分量和预乘计算时使用
                int color;

                // 🔍 使用异或运算判断棋盘格颜色
                // 💡 原理：(row & bit) ^ (col & bit) 的结果决定颜色交替
                // 💡 bit=0x01时：每个像素交替（最细粒度）
                // 💡 bit=0x02时：每2像素交替
                // 💡 bit=0x04时：每4像素交替
                // 💡 bit=0x08时：每8像素交替（最粗粒度）
                if (((row & bit) ^ (col & bit)) == 0) {
                    color = color1;  // 🎨 使用第一种颜色
                } else {
                    color = color2;  // 🎨 使用第二种颜色
                }

                // extract RGBA; use "int" instead of "byte" to get unsigned values
                // 🔍 提取RGBA分量（使用int而非byte以获取无符号值0-255）
                // 💡 为什么用int：Java的byte是有符号的（-128到127），用int可以正确处理0-255

                // 🔴 red：红色分量（0-255）
                // 💡 为什么定义：需要单独提取用于预乘计算
                // 💡 作用：存储颜色的红色通道值
                // 💡 使用时机：在预乘计算 (red * alphaM) 时使用
                int red = color & 0xff;

                // 🟢 green：绿色分量（0-255）
                // 💡 为什么定义：需要单独提取用于预乘计算
                // 💡 作用：存储颜色的绿色通道值
                // 💡 使用时机：在预乘计算 (green * alphaM) 时使用
                int green = (color >> 8) & 0xff;

                // 🔵 blue：蓝色分量（0-255）
                // 💡 为什么定义：需要单独提取用于预乘计算
                // 💡 作用：存储颜色的蓝色通道值
                // 💡 使用时机：在预乘计算 (blue * alphaM) 时使用
                int blue = (color >> 16) & 0xff;

                // 🔲 alpha：透明度分量（0-255）
                // 💡 为什么定义：需要单独提取用于预乘计算和存储
                // 💡 作用：存储颜色的透明度值
                // 💡 使用时机：在计算alphaM和存储buf[offset+3]时使用
                int alpha = (color >> 24) & 0xff;

                // pre-multiply colors and store in buffer
                // 🎨 预乘颜色并存储到缓冲区
                // 💡 预乘公式：finalColor = originalColor * (alpha / 255)

                // 🔲 alphaM：归一化的alpha值（0.0-1.0）
                // 💡 为什么定义：用于将颜色值从0-255范围转换为预乘后的值
                // 💡 作用：作为预乘系数
                // 💡 使用时机：在计算预乘颜色 (red * alphaM) 时使用
                float alphaM = alpha / 255.0f;

                // 🔴 buf[offset]：预乘后的红色分量
                // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
                // 💡 作用：存储像素的R通道
                // 💡 使用时机：在创建纹理时作为像素数据的一部分
                buf[offset] = (byte) (red * alphaM);

                // 🟢 buf[offset+1]：预乘后的绿色分量
                // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
                // 💡 作用：存储像素的G通道
                // 💡 使用时机：在创建纹理时作为像素数据的一部分
                buf[offset+1] = (byte) (green * alphaM);

                // 🔵 buf[offset+2]：预乘后的蓝色分量
                // 💡 为什么存储：OpenGL需要预乘alpha的颜色格式
                // 💡 作用：存储像素的B通道
                // 💡 使用时机：在创建纹理时作为像素数据的一部分
                buf[offset+2] = (byte) (blue * alphaM);

                // 🔲 buf[offset+3]：原始alpha值（不预乘）
                // 💡 为什么存储：alpha通道保持原始值，不参与预乘
                // 💡 作用：存储像素的A通道
                // 💡 使用时机：在创建纹理时作为像素数据的一部分
                buf[offset+3] = (byte) alpha;
            }
        }
    }
}
