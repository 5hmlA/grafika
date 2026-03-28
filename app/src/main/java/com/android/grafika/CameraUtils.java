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

import android.hardware.Camera;
import android.util.Log;
import java.util.List;

/**
 * Camera-related utility functions.
 * 
 * 📷 摄像头相关工具函数
 */
public class CameraUtils {
    private static final String TAG = MainActivity.TAG;

    /**
     * Attempts to find a preview size that matches the provided width and height (which
     * specify the dimensions of the encoded video).  If it fails to find a match it just
     * uses the default preview size for video.
     * <p>
     * TODO: should do a best-fit match
     * 
     * 📐 尝试找到匹配指定宽高的预览尺寸
     * 💡 如果找不到匹配项，使用视频的默认预览尺寸
     * @param parms 摄像头参数
     * @param width 期望宽度
     * @param height 期望高度
     */
    public static void choosePreviewSize(Camera.Parameters parms, int width, int height) {
        // We should make sure that the requested MPEG size is less than the preferred
        // size, and has the same aspect ratio.
        // 📐 获取摄像头推荐的视频预览尺寸（为什么：摄像头有最佳的预览尺寸）
        // 🎯 作用：获取摄像头针对视频优化的推荐尺寸
        // ⏰ 使用时机：方法开始时获取，作为备选方案
        Camera.Size ppsfv = parms.getPreferredPreviewSizeForVideo();
        // ❓ 检查推荐尺寸是否存在（为什么：某些摄像头可能不提供推荐尺寸）
        // 🎯 作用：避免空指针异常，同时记录推荐尺寸信息
        // ⏰ 使用时机：获取推荐尺寸后立即检查
        if (ppsfv != null) {
            // 📝 记录调试日志（为什么：便于调试确认推荐尺寸）
            // 🎯 作用：在Logcat输出摄像头推荐的预览尺寸
            // ⏰ 使用时机：推荐尺寸存在时记录
            Log.d(TAG, "Camera preferred preview size for video is " +
                    ppsfv.width + "x" + ppsfv.height);
        }

        //for (Camera.Size size : parms.getSupportedPreviewSizes()) {
        //    Log.d(TAG, "supported: " + size.width + "x" + size.height);
        //}

        // 🔄 遍历所有支持的预览尺寸（为什么：需要找到匹配的尺寸）
        // 🎯 作用：逐个检查支持的尺寸，寻找精确匹配
        // ⏰ 使用时机：记录推荐尺寸后开始搜索
        for (Camera.Size size : parms.getSupportedPreviewSizes()) {
            // ✅ 检查是否精确匹配（为什么：优先使用用户请求的尺寸）
            // 🎯 作用：判断当前尺寸是否与请求尺寸一致
            // ⏰ 使用时机：每次遍历时检查
            if (size.width == width && size.height == height) {
                // 📐 设置预览尺寸（为什么：应用用户请求的尺寸）
                // 🎯 作用：配置摄像头使用指定的预览尺寸
                // ⏰ 使用时机：找到匹配尺寸后立即设置
                parms.setPreviewSize(width, height);
                // 🚪 提前返回（为什么：已找到匹配尺寸，无需继续搜索）
                // 🎯 作用：结束方法执行
                // ⏰ 使用时机：尺寸设置完成后
                return;
            }
        }

        // ⚠️ 记录警告日志（为什么：未找到精确匹配的尺寸）
        // 🎯 作用：提示开发者尺寸设置失败
        // ⏰ 使用时机：遍历结束后仍未找到匹配
        Log.w(TAG, "Unable to set preview size to " + width + "x" + height);
        // ❓ 检查是否有推荐尺寸可用（为什么：需要使用备选方案）
        // 🎯 作用：确定是否可以使用推荐尺寸作为替代
        // ⏰ 使用时机：精确匹配失败后
        if (ppsfv != null) {
            // 📐 使用推荐尺寸作为备选（为什么：无法精确匹配时使用推荐值）
            // 🎯 作用：配置摄像头使用推荐的预览尺寸
            // ⏰ 使用时机：精确匹配失败且推荐尺寸存在时
            parms.setPreviewSize(ppsfv.width, ppsfv.height);
        }
        // else use whatever the default size is
    }

    /**
     * Attempts to find a fixed preview frame rate that matches the desired frame rate.
     * <p>
     * It doesn't seem like there's a great deal of flexibility here.
     * <p>
     * TODO: follow the recipe from http://stackoverflow.com/questions/22639336/#22645327
     *
     * 🎬 尝试找到匹配期望帧率的固定预览帧率
     * @param parms 摄像头参数
     * @param desiredThousandFps 期望的帧率（千分之一帧/秒）
     * @return 实际帧率（千分之一帧/秒）
     */
    public static int chooseFixedPreviewFps(Camera.Parameters parms, int desiredThousandFps) {
        // 📦 获取支持的预览帧率范围列表（为什么：摄像头支持的帧率不是任意值）
        // 🎯 作用：获取摄像头所有可用的帧率范围
        // ⏰ 使用时机：方法开始时获取，用于搜索匹配帧率
        List<int[]> supported = parms.getSupportedPreviewFpsRange();

        // 🔄 遍历所有支持的帧率范围（为什么：需要找到固定帧率）
        // 🎯 作用：逐个检查帧率范围，寻找固定帧率
        // ⏰ 使用时机：获取支持列表后立即开始遍历
        for (int[] entry : supported) {
            //Log.d(TAG, "entry: " + entry[0] + " - " + entry[1]);
            // ✅ 检查是否为固定帧率且匹配（为什么：entry[0]==entry[1]表示固定帧率）
            // 🎯 作用：判断当前范围是否为固定帧率且与期望值一致
            // ⏰ 使用时机：每次遍历时检查
            // 💡 entry[0]是最小帧率，entry[1]是最大帧率
            if ((entry[0] == entry[1]) && (entry[0] == desiredThousandFps)) {
                // 📐 设置预览帧率范围（为什么：应用找到的固定帧率）
                // 🎯 作用：配置摄像头使用指定的帧率范围
                // ⏰ 使用时机：找到匹配帧率后立即设置
                parms.setPreviewFpsRange(entry[0], entry[1]);
                // 🎯 返回实际帧率（为什么：告知调用者使用的帧率值）
                // 🎯 作用：返回找到的帧率，供调用者使用
                // ⏰ 使用时机：帧率设置完成后返回
                return entry[0];
            }
        }

        // 📦 创建临时数组存储当前帧率范围（为什么：需要获取当前帧率作为备选）
        // 🎯 作用：存储从getPreviewFpsRange()获取的帧率范围
        // ⏰ 使用时机：未找到固定帧率时，用于获取备选方案
        int[] tmp = new int[2];
        // 📐 获取当前预览帧率范围（为什么：了解摄像头当前的帧率设置）
        // 🎯 作用：填充tmp数组，获取最小和最大帧率
        // ⏰ 使用时机：创建tmp数组后立即调用
        parms.getPreviewFpsRange(tmp);
        // 📦 声明猜测的帧率值（为什么：需要存储最终的帧率猜测结果）
        // 🎯 作用：保存计算出的帧率值
        // ⏰ 使用时机：在条件判断后赋值
        int guess;
        // ❓ 检查当前是否为固定帧率（为什么：如果已经是固定帧率可以直接使用）
        // 🎯 作用：判断tmp[0]==tmp[1]是否为真
        // ⏰ 使用时机：获取帧率范围后立即判断
        if (tmp[0] == tmp[1]) {
            // 📐 使用固定帧率作为猜测值（为什么：当前帧率已经是固定的）
            // 🎯 作用：将固定帧率作为最终结果
            // ⏰ 使用时机：当前为固定帧率时
            guess = tmp[0];
        } else {
            // 📐 使用最大帧率的一半作为猜测值（为什么：需要一个合理的中间值）
            // 🎯 作用：计算一个保守的帧率估计
            // ⏰ 使用时机：当前为范围帧率时
            // 💡 这是一个简单启发式，可能不是最优解
            guess = tmp[1] / 2;     // shrug
        }

        // 📝 记录调试日志（为什么：便于调试确认帧率选择过程）
        // 🎯 作用：在Logcat输出未找到匹配和使用的猜测值
        // ⏰ 使用时机：确定猜测值后记录
        Log.d(TAG, "Couldn't find match for " + desiredThousandFps + ", using " + guess);
        // 🎯 返回猜测的帧率值（为什么：无法找到精确匹配时使用猜测值）
        // 🎯 作用：返回最终确定的帧率
        // ⏰ 使用时机：方法结束时返回给调用者
        return guess;
    }
}
