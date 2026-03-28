/*
 * Copyright 2018 Google LLC
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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Helper class for handling dangerous permissions for Android API level >= 23 which
 * requires user consent at runtime to access the camera.
 * 
 * 🔐 权限帮助类：处理Android 6.0+的危险权限
 * 💡 需要用户在运行时同意才能访问摄像头等敏感资源
 */
class PermissionHelper {
  // 🔢 权限请求码
  public static final int RC_PERMISSION_REQUEST = 9222;

  /**
   * 检查是否有摄像头权限
   * @param activity Activity对象
   * @return true表示已授权
   */
  public static boolean hasCameraPermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * 检查是否有写外部存储权限
   * @param activity Activity对象
   * @return true表示已授权
   */
  public static boolean hasWriteStoragePermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * 请求摄像头权限
   * 💡 如果用户之前拒绝过，会显示解释说明
   * @param activity Activity对象
   * @param requestWritePermission 是否同时请求写存储权限
   */
  public static void requestCameraPermission(Activity activity, boolean requestWritePermission) {
    // 🔍 showRationale - 是否需要向用户解释权限用途
    // 📌 作用：判断用户之前是否拒绝过该权限
    // 💡 返回true表示用户之前拒绝过，应该先解释为什么需要该权限
    // ⏰ 使用时机：请求权限前检查，决定是直接请求还是先显示说明
    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity,
              Manifest.permission.CAMERA) || (requestWritePermission &&
    ActivityCompat.shouldShowRequestPermissionRationale(activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE));
    // 🔍 判断是否需要先显示权限说明
    // 📌 作用：根据用户历史拒绝情况决定下一步操作
    // ⏰ 使用时机：检查完 showRationale 后立即判断
    if (showRationale) {
        // 💬 显示 Toast 提示用户为什么需要摄像头权限
        // 📌 作用：在请求权限前先向用户解释必要性
        // 💡 只有用户之前拒绝过才会走到这里
        Toast.makeText(activity,
                "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
      } else {
      // 📦 permissions - 要请求的权限数组
      // 📌 作用：根据参数决定是否同时请求存储权限
      // 💡 如果 requestWritePermission=true，则同时请求摄像头和存储权限
      // ⏰ 使用时机：直接请求权限时使用
      String permissions[] = requestWritePermission ? new String[]{Manifest.permission.CAMERA,
              Manifest.permission.WRITE_EXTERNAL_STORAGE}: new String[]{Manifest.permission.CAMERA};
        // 🚀 调用系统权限请求对话框
        // 📌 作用：向用户请求指定的权限
        // 💡 RC_PERMISSION_REQUEST 用于在回调中识别这次请求
        // ⏰ 使用时机：用户未拒绝过或拒绝后重新请求时
        ActivityCompat.requestPermissions(activity,permissions,RC_PERMISSION_REQUEST);
      }
    }

  /**
   * 请求写外部存储权限
   * @param activity Activity对象
   */
  public static void requestWriteStoragePermission(Activity activity) {
    // 🔍 showRationale - 是否需要向用户解释写存储权限用途
    // 📌 作用：检查用户之前是否拒绝过写存储权限
    // 💡 返回true表示应该先解释为什么需要该权限
    // ⏰ 使用时机：请求权限前检查，决定是直接请求还是先显示说明
    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
    // 🔍 判断是否需要先显示权限说明
    // 📌 作用：根据用户历史拒绝情况决定下一步操作
    // ⏰ 使用时机：检查完 showRationale 后立即判断
    if (showRationale) {
      // 💬 显示 Toast 提示用户为什么需要写存储权限
      // 📌 作用：在请求权限前先向用户解释必要性
      // 💡 只有用户之前拒绝过才会走到这里
      Toast.makeText(activity,
              "Writing to external storage permission is needed to run this application",
              Toast.LENGTH_LONG).show();
    } else {
      // 📦 permissions - 要请求的权限数组（仅写存储权限）
      // 📌 作用：封装写存储权限为数组格式，供系统API使用
      // 💡 系统API要求权限以字符串数组形式传入
      // ⏰ 使用时机：直接请求权限时使用
      String permissions[] =  new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE};
      // 🚀 调用系统权限请求对话框
      // 📌 作用：向用户请求写存储权限
      // 💡 RC_PERMISSION_REQUEST 用于在回调中识别这次请求
      // ⏰ 使用时机：用户未拒绝过或拒绝后重新请求时
      ActivityCompat.requestPermissions(activity,permissions,RC_PERMISSION_REQUEST);
    }
  }

  /**
   * Launch Application Setting to grant permission.
   * 
   * ⚙️ 启动应用设置页面，让用户手动授予权限
   * @param activity Activity对象
   */
  public static void launchPermissionSettings(Activity activity) {
    // 📦 intent - 意图对象
    // 💡 为什么定义：Android使用Intent来启动其他Activity或系统设置页面
    // 💡 作用：封装要执行的操作和目标信息
    // ⏰ 使用时机：方法开头创建，后续配置动作和数据
    Intent intent = new Intent();

    // ⚙️ 设置动作为打开应用详情设置页面
    // 💡 作用：告诉系统要打开的是应用的详细设置页面
    // 💡 Settings.ACTION_APPLICATION_DETAILS_SETTINGS 是系统预定义的动作
    // ⏰ 使用时机：创建Intent后立即设置
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);

    // 📦 设置数据URI，指定要查看哪个应用
    // 💡 作用：通过包名定位到当前应用的设置页面
    // 💡 Uri.fromParts() 将包名组装成URI格式
    // 💡 参数："package"是URI的scheme，activity.getPackageName()是当前应用包名
    // ⏰ 使用时机：设置动作后设置数据
    intent.setData(Uri.fromParts("package", activity.getPackageName(), null));

    // 🚀 启动设置页面
    // 💡 作用：调用系统打开应用设置页面，用户可以在那里手动授予权限
    // 💡 时机：所有配置完成后最后调用
    activity.startActivity(intent);
  }
}
