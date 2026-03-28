/*
 * Copyright 2013 Google Inc. All rights reserved.
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

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some handy utilities.
 * 
 * 🔧 一些实用工具函数
 */
public class MiscUtils {
    private static final String TAG = MainActivity.TAG;

    private MiscUtils() {}

    /**
     * Obtains a list of files that live in the specified directory and match the glob pattern.
     * 
     * 📁 获取指定目录下匹配glob模式的文件列表
     * @param dir 目录
     * @param glob glob模式（如"*.mp4"）
     * @return 匹配的文件名数组
     */
    public static String[] getFiles(File dir, String glob) {
        // 🔄 将glob模式转换为正则表达式（为什么：文件过滤需要使用正则）
        // 🎯 作用：将*.mp4等glob模式转换为可匹配的正则表达式
        // ⏰ 使用时机：方法开始时转换，用于后续的模式匹配
        String regex = globToRegex(glob);
        // 📦 编译正则表达式为Pattern对象（为什么：提高匹配性能）
        // 🎯 作用：将正则字符串编译为可重用的Pattern
        // ⏰ 使用时机：转换正则后立即编译
        // 💡 使用final修饰，确保在匿名内部类中可访问
        final Pattern pattern = Pattern.compile(regex);
        // 📁 列出匹配的文件（为什么：需要获取符合模式的文件名）
        // 🎯 作用：根据FilenameFilter过滤目录中的文件
        // ⏰ 使用时机：Pattern编译后开始过滤
        // 💡 返回的是匹配文件名的数组
        String[] result = dir.list(new FilenameFilter() {
            @Override public boolean accept(File dir, String name) {
                // 🔍 创建匹配器（为什么：需要检查文件名是否匹配模式）
                // 🎯 作用：将文件名与正则模式进行匹配
                // ⏰ 使用时机：每次检查文件名时创建
                Matcher matcher = pattern.matcher(name);
                // ✅ 返回是否匹配（为什么：决定文件是否包含在结果中）
                // 🎯 作用：判断文件名是否符合glob模式
                // ⏰ 使用时机：匹配检查完成后返回
                return matcher.matches();
            }
        });
        // 🔀 对结果数组排序（为什么：提供有序的文件列表）
        // 🎯 作用：按字母顺序排列文件名
        // ⏰ 使用时机：过滤完成后排序
        Arrays.sort(result);
        // 📤 返回排序后的文件名数组（为什么：调用者需要使用结果）
        // 🎯 作用：提供最终的文件列表
        // ⏰ 使用时机：方法结束时返回
        return result;
    }

    /**
     * Converts a filename globbing pattern to a regular expression.
     * 
     * 🔄 将文件名glob模式转换为正则表达式
     * @param glob glob模式
     * @return 正则表达式字符串
     */
    private static String globToRegex(String glob) {
        // Quick, overly-simplistic implementation -- just want to handle something simple
        // like "*.mp4".
        // 📦 创建StringBuilder构建正则表达式（为什么：需要动态拼接正则字符串）
        // 🎯 作用：高效构建最终的正则表达式
        // ⏰ 使用时机：方法开始时创建，用于累积转换结果
        // 💡 初始容量设为glob长度，避免频繁扩容
        StringBuilder regex = new StringBuilder(glob.length());
        //regex.append('^');
        // 🔄 遍历glob模式的每个字符（为什么：需要逐个转换特殊字符）
        // 🎯 作用：处理glob模式中的每个字符
        // ⏰ 使用时机：StringBuilder创建后立即开始遍历
        for (char ch : glob.toCharArray()) {
            // 🔀 根据字符类型进行转换（为什么：不同字符有不同的正则含义）
            // 🎯 作用：将glob特殊字符转换为对应的正则表达式
            // ⏰ 使用时机：每次遍历字符时执行
            switch (ch) {
                case '*':
                    // 🌟 glob的*转换为正则的.*（为什么：*在glob中表示任意字符序列）
                    // 🎯 作用：匹配零个或多个任意字符
                    // ⏰ 使用时机：遇到*字符时
                    regex.append(".*");
                    break;
                case '?':
                    // ❓ glob的?转换为正则的.（为什么：?在glob中表示单个任意字符）
                    // 🎯 作用：匹配单个任意字符
                    // ⏰ 使用时机：遇到?字符时
                    regex.append('.');
                    break;
                case '.':
                    // 📍 点号需要转义（为什么：.在正则中表示任意字符，需要转义）
                    // 🎯 作用：匹配字面意义上的点号
                    // ⏰ 使用时机：遇到.字符时
                    regex.append("\\.");
                    break;
                default:
                    // 📝 其他字符直接添加（为什么：普通字符不需要转换）
                    // 🎯 作用：保持原样添加到正则表达式
                    // ⏰ 使用时机：遇到非特殊字符时
                    regex.append(ch);
                    break;
            }
        }
        //regex.append('$');
        // 🎯 返回构建好的正则表达式字符串（为什么：调用者需要使用转换结果）
        // 🎯 作用：提供最终的正则表达式
        // ⏰ 使用时机：所有字符处理完成后返回
        return regex.toString();
    }

    /**
     * Obtains the approximate refresh time, in nanoseconds, of the default display associated
     * with the activity.
     * <p>
     * The actual refresh rate can vary slightly (e.g. 58-62fps on a 60fps device).
     * 
     * ⏱️ 获取Activity关联的默认显示器的近似刷新时间（纳秒）
     * 💡 实际刷新率可能略有变化（例如60fps设备上为58-62fps）
     * @param activity Activity对象
     * @return 刷新时间（纳秒）
     */
    public static long getDisplayRefreshNsec(Activity activity) {
        // 📦 获取WindowManager并获取默认显示器（为什么：需要访问显示器的刷新率信息）
        // 🎯 作用：通过系统服务获取Display对象
        // ⏰ 使用时机：方法开始时获取，用于查询刷新率
        // 💡 先获取WINDOW_SERVICE，再调用getDefaultDisplay()
        Display display = ((WindowManager)
                activity.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        // 📐 获取显示器的刷新率（为什么：需要知道屏幕每秒刷新多少次）
        // 🎯 作用：获取刷新率（单位：帧/秒）
        // ⏰ 使用时机：获取Display对象后立即查询
        double displayFps = display.getRefreshRate();
        // 🧮 计算刷新时间（纳秒）（为什么：需要将帧率转换为时间间隔）
        // 🎯 作用：计算每帧的时间（1秒/帧率 = 每帧时间）
        // ⏰ 使用时机：获取刷新率后立即计算
        // 💡 1000000000L表示1秒的纳秒数
        long refreshNs = Math.round(1000000000L / displayFps);
        // 📝 记录调试日志（为什么：便于调试确认计算结果）
        // 🎯 作用：在Logcat输出刷新率和计算结果
        // ⏰ 使用时机：计算完成后记录
        Log.d(TAG, "refresh rate is " + displayFps + " fps --> " + refreshNs + " ns");
        // 🎯 返回刷新时间（纳秒）（为什么：调用者需要使用这个值）
        // 🎯 作用：提供显示器的刷新时间间隔
        // ⏰ 使用时机：方法结束时返回
        return refreshNs;
    }
}
