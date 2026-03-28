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

package com.android.grafika;

import android.opengl.EGL14;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.app.Activity;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.OffscreenSurface;
import com.google.grafika.R;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 * Simple activity that gathers and displays information from the GLES driver.
 * 
 * ℹ️ OpenGL ES信息显示Activity
 * 💡 收集并显示GL驱动信息
 */
public class GlesInfoActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private String mGlInfo;      // 📝 GL信息字符串
    private File mOutputFile;    // 📁 输出文件

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 调用父类onCreate（为什么：Activity生命周期要求）
        // 💡 作用：执行Activity的初始化逻辑
        // ⏰ 使用时机：Activity创建时首先调用
        super.onCreate(savedInstanceState);

        // 🎨 设置布局文件（为什么：需要定义UI界面）
        // 💡 作用：将XML布局加载为当前Activity的视图
        // ⏰ 使用时机：super.onCreate()后立即设置
        setContentView(R.layout.activity_gles_info);

        // 📁 创建输出文件对象（为什么：GL信息需要保存到文件）
        // 💡 作用：定义GL信息文件的存储路径
        // ⏰ 使用时机：布局设置后创建，供后续保存使用
        // 💡 路径：应用内部存储目录下的gles-info.txt
        mOutputFile = new File(getFilesDir(), "gles-info.txt");

        // 📝 获取显示文件路径的TextView（为什么：需要向用户展示输出文件位置）
        // 💡 作用：显示GL信息文件的完整路径
        // ⏰ 使用时机：创建文件对象后获取视图
        TextView tv = (TextView) findViewById(R.id.glesInfoFile_text);

        // 📋 设置文件路径文本（为什么：让用户知道文件保存在哪里）
        // 💡 作用：将文件路径显示在TextView中
        // ⏰ 使用时机：获取TextView后立即设置
        tv.setText(mOutputFile.toString());

        // 🎮 收集GL和EGL信息（为什么：需要获取设备的图形能力信息）
        // 💡 作用：调用gatherGlInfo()查询所有GL/EGL/系统信息
        // ⏰ 使用时机：布局和文件路径设置完成后
        mGlInfo = gatherGlInfo();

        // 📝 获取显示GL信息的TextView（为什么：需要向用户展示收集到的信息）
        // 💡 作用：显示GL、EGL和系统信息的详细内容
        // ⏰ 使用时机：收集完GL信息后获取视图
        tv = (TextView) findViewById(R.id.glesInfo_text);

        // 📋 设置GL信息文本（为什么：将收集到的信息展示给用户）
        // 💡 作用：将所有收集的信息显示在TextView中
        // ⏰ 使用时机：获取TextView后，Activity创建完成时
        tv.setText(mGlInfo);
    }

    /**
     * 💾 保存按钮点击处理
     * 💡 将收集的GL/EGL/系统信息保存到文件
     * 💡 作用：将mGlInfo字符串持久化到gles-info.txt文件
     *
     * @param unused 未使用的View参数（onClick方法要求的参数）
     */
    public void clickSave(@SuppressWarnings("unused") View unused) {
        try {
            // ✍️ 创建文件写入器（为什么：需要将GL信息写入文件）
            // 💡 作用：提供文件写入能力
            // ⏰ 使用时机：按钮点击后立即创建
            // 💡 参数：mOutputFile是在onCreate中创建的输出文件
            FileWriter writer = new FileWriter(mOutputFile);

            // 📝 写入GL信息到文件（为什么：将收集的信息持久化保存）
            // 💡 作用：将mGlInfo字符串写入文件
            // ⏰ 使用时机：FileWriter创建后立即写入
            writer.write(mGlInfo);

            // 🔒 关闭写入器（为什么：释放文件资源，确保数据写入磁盘）
            // 💡 作用：刷新缓冲区并关闭文件
            // ⏰ 使用时机：写入完成后立即关闭
            writer.close();

            // 📋 记录成功日志（为什么：便于调试确认保存成功）
            // 💡 作用：在Logcat输出保存成功的消息
            // ⏰ 使用时机：文件关闭后记录
            Log.d(TAG, "Output written to '" + mOutputFile + "'");
        } catch (IOException ioe) {
            // ❌ 记录错误日志（为什么：文件写入失败需要记录错误信息）
            // 💡 作用：在Logcat输出错误详情
            // ⏰ 使用时机：捕获IOException时
            Log.e(TAG, "Failed writing file", ioe);
        }
    }

    /**
     * ℹ️ 收集GL和EGL信息
     * 💡 创建临时EGL上下文，查询OpenGL ES和EGL驱动的详细信息
     * 💡 同时收集设备系统信息，便于调试和问题排查
     */
    private String gatherGlInfo() {
        // 🎮 创建EGL核心对象，尝试使用GLES3
        // 💡 作用：初始化EGL环境，用于查询GL信息
        // 💡 参数：null表示使用默认显示设备，FLAG_TRY_GLES3表示优先尝试GLES3
        // 💡 时机：方法开始时创建，用于后续GL信息查询
        EglCore eglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);

        // 🖼️ 创建离屏Surface（1x1像素），用于激活GL上下文
        // 💡 作用：提供一个有效的渲染表面，使GL上下文可以被激活
        // 💡 参数：EGL核心对象、宽度1像素、高度1像素（最小尺寸）
        // 💡 时机：EGL核心创建后立即创建
        OffscreenSurface surface = new OffscreenSurface(eglCore, 1, 1);

        // 🔌 激活GL上下文，使GL查询函数可用
        // 💡 作用：将当前线程与EGL上下文关联
        // 💡 时机：Surface创建后，查询GL信息前必须调用
        surface.makeCurrent();

        // 📝 创建字符串构建器，用于拼接所有信息
        // 💡 作用：高效构建多行文本信息
        // 💡 时机：开始收集信息时创建，方法结束时转换为String返回
        StringBuilder sb = new StringBuilder();

        // 📋 添加GL信息标题
        // 💡 作用：标记GL信息部分的开始
        sb.append("===== GL Information =====");

        // 🏭 获取GL供应商信息（如ARM、Qualcomm等GPU厂商）
        // 💡 作用：显示GPU驱动的开发商信息
        // 💡 常见值：ARM, Qualcomm, NVIDIA等
        sb.append("\nvendor    : ").append(GLES20.glGetString(GLES20.GL_VENDOR));

        // 🔢 获取GL版本信息（如OpenGL ES 3.2）
        // 💡 作用：显示设备支持的OpenGL ES版本
        // 💡 格式：OpenGL ES <主版本>.<次版本> <厂商特定信息>
        sb.append("\nversion   : ").append(GLES20.glGetString(GLES20.GL_VERSION));

        // 🎨 获取渲染器信息（如Mali-G78, Adreno 650等）
        // 💡 作用：显示具体的GPU型号信息
        // 💡 用于：识别设备的具体GPU能力
        sb.append("\nrenderer  : ").append(GLES20.glGetString(GLES20.GL_RENDERER));

        // 📦 获取GL扩展列表（支持的额外功能）
        // 💡 作用：显示GPU支持的所有扩展功能
        // 💡 格式化：调用formatExtensions进行排序和分行显示
        sb.append("\nextensions:\n").append(formatExtensions(GLES20.glGetString(GLES20.GL_EXTENSIONS)));

        // 📋 添加EGL信息标题
        // 💡 作用：标记EGL信息部分的开始
        sb.append("\n===== EGL Information =====");

        // 🏭 获取EGL供应商信息
        // 💡 作用：显示EGL实现的开发商
        // 💡 常见值：Android, Mesa等
        sb.append("\nvendor    : ").append(eglCore.queryString(EGL14.EGL_VENDOR));

        // 🔢 获取EGL版本信息
        // 💡 作用：显示EGL实现的版本
        // 💡 格式：<主版本>.<次版本>
        sb.append("\nversion   : ").append(eglCore.queryString(EGL14.EGL_VERSION));

        // 🎮 获取支持的客户端API
        // 💡 作用：显示EGL支持的渲染API（如OpenGL ES, OpenVG）
        // 💡 常见值：OpenGL_ES
        sb.append("\nclient API: ").append(eglCore.queryString(EGL14.EGL_CLIENT_APIS));

        // 📦 获取EGL扩展列表
        // 💡 作用：显示EGL支持的扩展功能
        // 💡 用于：判断设备是否支持特定EGL功能
        sb.append("\nextensions:\n").append(formatExtensions(eglCore.queryString(EGL14.EGL_EXTENSIONS)));

        // 🗑️ 释放离屏Surface资源
        // 💡 作用：清理渲染表面占用的资源
        // 💡 时机：GL信息查询完成后立即释放
        surface.release();

        // 🗑️ 释放EGL核心资源
        // 💡 作用：清理EGL上下文占用的资源
        // 💡 时机：Surface释放后，避免资源泄漏
        eglCore.release();

        // 📋 添加系统信息标题
        // 💡 作用：标记系统信息部分的开始
        sb.append("\n===== System Information =====");

        // 🏭 获取设备制造商（如samsung, HUAWEI等）
        // 💡 作用：识别设备的生产厂家
        // 💡 用于：设备兼容性问题排查
        sb.append("\nmfgr      : ").append(Build.MANUFACTURER);

        // 🏷️ 获取设备品牌（如Galaxy, Pixel等）
        // 💡 作用：识别设备的品牌系列
        // 💡 用于：更精确的设备定位
        sb.append("\nbrand     : ").append(Build.BRAND);

        // 📱 获取设备型号（如SM-G991B, Pixel 6等）
        // 💡 作用：识别具体设备型号
        // 💡 用于：特定设备的bug追踪
        sb.append("\nmodel     : ").append(Build.MODEL);

        // 🔢 获取Android版本号（如12, 13等）
        // 💡 作用：显示设备运行的Android版本
        // 💡 用于：判断API级别和功能可用性
        sb.append("\nrelease   : ").append(Build.VERSION.RELEASE);

        // 🔧 获取系统构建号/固件版本
        // 💡 作用：显示系统的具体构建标识
        // 💡 用于：识别系统更新状态
        sb.append("\nbuild     : ").append(Build.DISPLAY);

        // 📝 添加换行符作为结尾
        sb.append("\n");

        // 📤 返回收集的所有信息字符串
        // 💡 作用：将所有收集的信息返回给调用者
        // 💡 时机：所有信息收集完成后
        return sb.toString();
    }

    /**
     * 📦 格式化扩展字符串（排序后分行显示）
     * 💡 将空格分隔的扩展名列表转换为排序后的分行格式
     * 💡 作用：提高扩展列表的可读性
     *
     * @param ext 空格分隔的扩展名字符串
     * @return 格式化后的扩展名字符串（每行一个，带缩进）
     */
    private String formatExtensions(String ext) {
        // ✂️ 将扩展字符串按空格分割为数组
        // 💡 作用：将连续的扩展名字符串拆分为独立项
        // 💡 时机：方法开始时立即分割
        String[] values = ext.split(" ");

        // 🔤 对扩展名数组进行排序
        // 💡 作用：按字母顺序排列，便于查找和阅读
        // 💡 时机：分割后立即排序
        Arrays.sort(values);

        // 📝 创建字符串构建器
        // 💡 作用：高效拼接多行文本
        // 💡 时机：排序后创建，用于构建结果
        StringBuilder sb = new StringBuilder();

        // 📋 遍历每个扩展名
        // 💡 作用：为每个扩展名添加缩进和换行
        // 💡 时机：排序完成后遍历
        for (String value : values) {
            // 📝 添加带缩进的扩展名
            // 💡 作用：格式化为"  扩展名\n"的形式
            // 💡 缩进（两个空格）用于与标题区分
            sb.append("  ").append(value).append("\n");
        }

        // 📤 返回格式化后的字符串
        // 💡 作用：将构建器内容转换为String返回
        // 💡 时机：所有扩展名处理完成后返回
        return sb.toString();
    }
}
