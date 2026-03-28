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
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import android.view.InflateException;
import android.view.View;
import com.google.grafika.R;

/**
 * Creates and displays an "about" box.
 * 
 * ℹ️ 创建并显示"关于"对话框
 */
public class AboutBox {
    private static final String TAG = MainActivity.TAG;

    /**
     * Retrieves the application's version info.
     * 
     * 📦 获取应用版本信息
     * @return 版本字符串，格式为"版本名 [版本号]"
     */
    private static String getVersionString(Context context) {
        // 📦 获取PackageManager实例（为什么：需要查询应用包信息）
        // 🎯 作用：提供访问已安装应用包信息的接口
        // ⏰ 使用时机：方法开始时获取，用于后续查询版本信息
        PackageManager pman = context.getPackageManager();
        // 📦 获取当前应用的包名（为什么：需要知道要查询哪个应用的版本）
        // 🎯 作用：标识当前应用，用于精确查询
        // ⏰ 使用时机：在调用getPackageInfo()之前获取
        String packageName = context.getPackageName();
        try {
            // 📦 获取包信息对象（为什么：版本信息存储在PackageInfo中）
            // 🎯 作用：包含版本名、版本号等详细信息
            // ⏰ 使用时机：try块中，处理可能的包未找到异常
            // 💡 第二个参数0表示不获取额外信息
            PackageInfo pinfo = pman.getPackageInfo(packageName, 0);
            // 📝 记录调试日志（为什么：便于调试确认版本信息正确）
            // 🎯 作用：在Logcat中输出版本信息
            // ⏰ 使用时机：获取包信息成功后
            Log.d(TAG, "Found version " + pinfo.versionName + " for " + packageName);
            // 🎯 格式化版本字符串并返回（为什么：需要统一的版本显示格式）
            // 🎯 作用：将版本名和版本号组合成"版本名 [版本号]"格式
            // ⏰ 使用时机：方法结束时返回给调用者
            return pinfo.versionName + " [" + pinfo.versionCode + "]";
        } catch (NameNotFoundException nnfe) {
            // ⚠️ 记录警告日志（为什么：包未找到是异常情况，需要记录）
            // 🎯 作用：在Logcat中输出错误信息
            // ⏰ 使用时机：捕获异常时记录
            Log.w(TAG, "Unable to retrieve package info for " + packageName);
            // 🎯 返回未知版本标识（为什么：获取失败时需要返回有意义的值）
            // 🎯 作用：告知用户版本信息不可用
            // ⏰ 使用时机：异常处理结束时返回
            return "(unknown)";
        }
    }

    /**
     * Displays the About box.  An AlertDialog is created in the calling activity's context.
     * <p>
     * The box will disappear if the "OK" button is touched, if an area outside the box is
     * touched, if the screen is rotated ... doing just about anything makes it disappear.
     * 
     * 📢 显示关于对话框
     * 💡 点击"确定"、触摸外部区域或旋转屏幕都会使对话框消失
     * @param caller 调用者Activity
     */
    public static void display(Activity caller) {
        // 📦 获取版本字符串（为什么：关于对话框需要显示应用版本）
        // 🎯 作用：获取格式化的版本信息
        // ⏰ 使用时机：创建对话框标题之前
        String versionStr = getVersionString(caller);
        // 📝 构建关于对话框的标题（为什么：需要显示应用名称和版本）
        // 🎯 作用：将应用名和版本组合成标题
        // ⏰ 使用时机：设置对话框标题之前
        // 💡 格式："应用名 vX.X.X [版本号]"
        String aboutHeader = caller.getString(R.string.app_name) + " v" + versionStr;

        // Manually inflate the view that will form the body of the dialog.
        // 👁️ 声明视图变量（为什么：需要持有对话框内容视图的引用）
        // 🎯 作用：存储从XML布局文件加载的视图对象
        // ⏰ 使用时机：在try块中加载，失败时返回null
        View aboutView;
        try {
            // 🎨 从布局文件加载关于对话框的视图（为什么：需要定义对话框的内容）
            // 🎯 作用：将XML布局转换为可显示的View对象
            // ⏰ 使用时机：创建对话框内容之前
            // 💡 inflate()的第二个参数null表示不立即添加到父容器
            aboutView = caller.getLayoutInflater().inflate(R.layout.about_dialog, null);
        } catch (InflateException ie) {
            // ❌ 记录错误日志（为什么：布局加载失败是严重错误）
            // 🎯 作用：在Logcat中输出错误详情
            // ⏰ 使用时机：捕获InflateException时
            Log.e(TAG, "Exception while inflating about box: " + ie.getMessage());
            // 🚪 提前返回（为什么：布局加载失败无法显示对话框）
            // 🎯 作用：避免后续代码执行导致崩溃
            // ⏰ 使用时机：记录错误后立即返回
            return;
        }

        // 🏗️ 创建AlertDialog构建器（为什么：需要构建对话框）
        // 🎯 作用：提供创建AlertDialog的工具类
        // ⏰ 使用时机：获取版本信息和布局视图之后
        AlertDialog.Builder builder = new AlertDialog.Builder(caller);
        // 📝 设置对话框标题（为什么：用户需要知道这是什么对话框）
        // 🎯 作用：显示应用名称和版本信息
        // ⏰ 使用时机：构建器创建后立即设置
        builder.setTitle(aboutHeader);
        // 🎨 设置对话框图标（为什么：增强视觉识别）
        // 🎯 作用：显示应用图标
        // ⏰ 使用时机：标题设置后
        builder.setIcon(R.drawable.ic_launcher);
        // ✅ 设置可取消（为什么：用户应该能够关闭对话框）
        // 🎯 作用：允许通过点击外部区域或返回键关闭
        // ⏰ 使用时机：图标设置后
        builder.setCancelable(true);        // implies setCanceledOnTouchOutside
        // 🔘 添加确定按钮（为什么：提供明确的关闭方式）
        // 🎯 作用：创建"确定"按钮，点击后关闭对话框
        // ⏰ 使用时机：可取消性设置后
        // 💡 第二个参数null表示使用默认的关闭行为
        builder.setPositiveButton(R.string.ok, null);
        // 👁️ 设置对话框内容视图（为什么：显示关于信息的详细内容）
        // 🎯 作用：将之前加载的布局视图作为对话框内容
        // ⏰ 使用时机：按钮设置后
        builder.setView(aboutView);
        // 🎬 显示对话框（为什么：将构建好的对话框呈现给用户）
        // 🎯 作用：在屏幕上显示关于对话框
        // ⏰ 使用时机：所有配置完成后，最后调用
        builder.show();
    }
}
