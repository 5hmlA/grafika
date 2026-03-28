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
import android.app.AlertDialog;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import com.google.grafika.R;

/**
 * Utility functions for work_dialog.
 * 
 * 🔧 工作对话框工具类
 */
public class WorkDialog {
    private static final String TAG = MainActivity.TAG;

    private WorkDialog() {}     // nah

    /**
     * Prepares an alert dialog builder, using the work_dialog view.
     * <p>
     * The caller should finish populating the builder, then call AlertDialog.Builder#show().
     * 
     * 📢 创建工作对话框构建器
     * @param activity Activity对象
     * @param titleId 标题字符串资源ID
     * @return AlertDialog.Builder对象
     */
    public static AlertDialog.Builder create(Activity activity, int titleId) {
        // 👁️ view: 声明视图变量
        // 💡 为什么定义：需要持有对话框内容视图的引用
        // 💡 作用：存储从XML布局文件加载的视图对象
        // 💡 使用时机：在try块中加载，成功后传入 builder.setView()
        View view;
        // 🔒 try：尝试从 XML 布局文件加载对话框视图
        // 💡 为什么使用：inflate() 可能因布局错误抛出 InflateException
        try {
            // 🎨 activity.getLayoutInflater().inflate()：从布局文件加载工作对话框的视图
            // 💡 为什么调用：需要定义对话框的内容区域
            // 💡 作用：将XML布局 (R.layout.work_dialog) 转换为可显示的View对象
            // 💡 使用时机：创建对话框内容之前
            // 💡 inflate()的第二个参数null表示不立即添加到父容器
            view = activity.getLayoutInflater().inflate(R.layout.work_dialog, null);
        // 🔒 catch (InflateException)：捕获布局加载异常
        // 💡 为什么捕获：XML 布局可能存在语法错误或引用了不存在的资源
        } catch (InflateException ie) {
            // ❌ Log.e()：记录错误日志
            // 💡 为什么记录：布局加载失败是严重错误，需要在Logcat中追踪
            // 💡 作用：在Logcat中输出错误详情（异常消息）
            // 💡 使用时机：捕获InflateException时立即记录
            Log.e(TAG, "Exception while inflating work dialog layout: " + ie.getMessage());
            // 🚪 throw ie：重新抛出异常
            // 💡 为什么抛出：调用者需要知道加载失败，无法继续创建对话框
            // 💡 作用：将错误传播给调用者处理
            // 💡 使用时机：记录错误后立即抛出
            throw ie;
        }

        // 📝 String title = activity.getString(titleId)：根据资源ID获取标题字符串
        // 💡 为什么定义：需要显示本地化的标题文字
        // 💡 作用：将资源ID (如 R.string.preparing_content) 转换为实际字符串
        // 💡 使用时机：创建对话框构建器之前
        String title = activity.getString(titleId);
        // 🏗️ AlertDialog.Builder builder：创建AlertDialog构建器
        // 💡 为什么定义：需要构建对话框的工具类
        // 💡 作用：提供设置标题、内容、按钮等对话框属性的方法
        // 💡 使用时机：获取标题字符串后创建
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        // 📝 builder.setTitle(title)：设置对话框标题
        // 💡 为什么调用：用户需要知道这是什么对话框
        // 💡 作用：显示工作对话框的标题文字
        // 💡 使用时机：构建器创建后立即设置
        builder.setTitle(title);
        // 👁️ builder.setView(view)：设置对话框内容视图
        // 💡 为什么调用：显示工作对话框的详细内容（进度条、文件名等）
        // 💡 作用：将之前加载的布局视图作为对话框主体内容
        // 💡 使用时机：标题设置后设置内容视图
        builder.setView(view);
        // 📤 return builder：返回配置好的构建器
        // 💡 为什么返回：调用者需要继续配置（如 setCancelable）或直接 show()
        // 💡 作用：提供可继续配置或直接显示的构建器对象
        // 💡 使用时机：所有基本配置完成后返回
        return builder;
   }
}
