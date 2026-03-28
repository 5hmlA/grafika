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

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.grafika.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Activity demonstrating the use of MediaProjectionManager and VirtualDisplay to create a
 * recording of the screen and save it as a movie.
 * <p>
 * This activity extends the PlayMovieSurfaceActivity so there is something going on in the activity
 * so the recording is more interesting :).
 * <p>
 * The APIs used require API level 23 (Marshmallow), which at the time of writing this (Jan. 2018)
 * covers ~54% of all Android devices see:
 * https://developer.android.com/about/dashboards/index.html
 *
 * 📱 屏幕录制Activity演示
 * 使用MediaProjectionManager和VirtualDisplay录制屏幕
 * 需要Android 6.0 (Marshmallow)或更高版本
 * 💡 继承PlayMovieSurfaceActivity使录制内容更有趣
 * 🎬 使用MediaCodec编码器和MediaMuxer混合器输出MP4文件
 */
public class ScreenRecordActivity extends PlayMovieSurfaceActivity {
  private static final String TAG = "ScreenRecordActivity";
  // 📊 媒体投影管理器和投影对象
  private MediaProjectionManager mediaProjectionManager;
  private MediaProjection mediaProjection;
  // 🎬 媒体混合器和编码器
  private MediaMuxer muxer;
  private Surface inputSurface;
  private MediaCodec videoEncoder;
  // 📊 混合器状态和轨道索引
  private boolean muxerStarted;
  private int trackIndex = -1;

  // 🔐 权限请求码和视频MIME类型
  private static final int REQUEST_CODE_CAPTURE_PERM = 1234;
  private static final String VIDEO_MIME_TYPE = "video/avc";

  // 📬 编码器回调
  private android.media.MediaCodec.Callback encoderCallback;

  // 🎯 获取内容视图ID
  @Override
  protected int getContentViewId() {
    return R.layout.activity_screen_record;
  }

  // 🎯 Activity创建时初始化（共100行，需逐行注释）
  // 🔧 为什么：初始化屏幕录制所需的UI按钮、权限管理器、编码器回调
  // 📍 时机：Activity首次创建时由系统调用
  @TargetApi(Build.VERSION_CODES.M) // 🏷️ 标注此方法需要API 23+
  @Override
  protected void onCreate(Bundle savedInstanceState) { // 📦 savedInstanceState：用于恢复Activity之前的状态（本例未使用）
    super.onCreate(savedInstanceState); // 📞 调用父类onCreate，完成基础Activity初始化

    // ⚠️ 检查Android版本（需要Marshmallow或更高）
    // 🔍 为什么：MediaProjection API需要API 23+，低版本会崩溃
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { // 📊 比较当前系统版本与Marshmallow(API 23)
      new AlertDialog.Builder(this) // 💬 创建一个错误提示对话框
              .setTitle("Error") // 🏷️ 设置对话框标题
              .setMessage("This activity only works on Marshmallow or later.") // 📝 设置错误信息
              .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() { // 🔘 设置确定按钮及点击回调
                @Override
                public void onClick(DialogInterface dialog, int which) { // 🖱️ 用户点击确定时触发
                  ScreenRecordActivity.this.finish(); // 🚪 关闭当前Activity，因为版本不支持
                }
              })
              .show(); // 👁️ 显示对话框
      return; // 🛑 提前返回，不执行后续初始化
    }


    // 🔘 设置录制按钮点击事件
    // 🎯 为什么：用户通过此按钮控制录制的开始和停止
    Button toggleRecording = findViewById(R.id.screen_record_button); // 🔍 获取录制按钮的View引用

    toggleRecording.setOnClickListener(new View.OnClickListener() { // 📌 为按钮设置点击监听器
      @RequiresApi(api = Build.VERSION_CODES.M) // 🏷️ 声明此回调需要API 23+
      @Override
      public void onClick(View v) { // 🖱️ 按钮被点击时执行
        if (v.getId() == R.id.screen_record_button) { // 🔍 确认点击的是录制按钮（避免误触发）
          if (muxerStarted) { // 🔀 如果混合器已启动，说明正在录制中
            // ⏹️ 停止录制
            stopRecording(); // 🛑 调用停止录制方法，释放编码器和混合器资源
            ((Button) findViewById(R.id.screen_record_button)).setText(R.string.toggleRecordingOn); // 📝 将按钮文字改为"开始录制"
          } else { // 🔀 混合器未启动，说明未在录制
            // ▶️ 请求屏幕录制权限
            // 📋 为什么：屏幕录制需要用户明确授权
            Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent(); // 🔐 创建屏幕捕获权限请求Intent
            startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM); // 🚀 启动权限请求Activity，结果码为1234
            findViewById(R.id.screen_record_button).setEnabled(false); // 🔒 禁用按钮，防止重复点击
          }
        }
      }

    }); // ✅ 点击监听器设置完成

    // 📊 获取媒体投影管理器服务
    // 🔍 为什么：MediaProjectionManager是获取屏幕录制权限和MediaProjection的入口
    // 📍 时机：在onCreate中初始化，供后续startRecording使用
    mediaProjectionManager = (MediaProjectionManager) getSystemService( // 📞 从系统服务获取MediaProjectionManager实例
            android.content.Context.MEDIA_PROJECTION_SERVICE); // 🏷️ 服务名称常量

    // 📬 初始化编码器回调
    // 🔧 为什么：MediaCodec异步模式需要Callback来处理编码输出
    // 📍 时机：在onCreate中设置，编码器启动后自动触发回调
    encoderCallback = new MediaCodec.Callback() { // 🎬 创建匿名Callback实现
      @Override
      public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { // 📥 输入缓冲区可用时回调（本例未使用输入缓冲区）
        Log.d(TAG, "Input Buffer Avail"); // 📝 仅记录日志，因为使用Surface输入不需要手动填充
      }

      @Override // 📤 输出缓冲区可用时回调，包含编码后的数据
      public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
        // 📦 encodedData：编码后的视频数据缓冲区
        // 🔍 为什么：需要从编码器取出编码数据写入混合器
        ByteBuffer encodedData = videoEncoder.getOutputBuffer(index); // 📞 根据索引获取输出缓冲区
        if (encodedData == null) { // ❌ 缓冲区为空表示索引无效
          throw new RuntimeException("couldn't fetch buffer at index " + index); // 💥 抛出异常，编码器状态异常
        }

        // 🚫 跳过编解码器配置数据
        // ⚠️ 为什么：BUFFER_FLAG_CODEC_CONFIG包含SPS/PPS等配置信息，不是实际帧数据
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { // 🔍 检查是否为配置数据标志
          info.size = 0; // 📝 将大小设为0，后续跳过此数据
        }

        // 📝 将编码数据写入混合器
        // 🔍 为什么：只有非空数据才需要写入MP4文件
        if (info.size != 0) { // 📊 检查数据大小不为0
          if (muxerStarted) { // 🔀 确保混合器已启动（格式已确定）
            encodedData.position(info.offset); // 📍 设置读取起始位置为BufferInfo记录的偏移量
            encodedData.limit(info.offset + info.size); // 📍 设置读取结束位置
            muxer.writeSampleData(trackIndex, encodedData, info); // ✍️ 将编码样本写入混合器的指定轨道
          }
        }

        // 🔄 释放输出缓冲区
        // 🔧 为什么：用完缓冲区必须归还给编码器，否则编码器会阻塞
        videoEncoder.releaseOutputBuffer(index, false); // 📞 归还缓冲区，false表示不渲染到Surface

      } // ✅ onOutputBufferAvailable结束

      @Override
      public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { // ❌ 编码器出错时回调
        Log.e(TAG, "MediaCodec " + codec.getName() + " onError:", e); // 📝 记录错误日志，包含编解码器名称和异常信息
      }

      @Override // 🔄 编码器输出格式变更时回调（通常在首次输出数据前触发）
      public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
        Log.d(TAG, "Output Format changed"); // 📝 记录格式变更日志
        // ⚠️ 检查格式是否已更改
        // 🔍 为什么：格式只应变更一次，重复变更说明编码器异常
        if (trackIndex >= 0) { // 📊 trackIndex >= 0 表示已经添加过轨道
          throw new RuntimeException("format changed twice"); // 💥 不应发生两次格式变更
        }
        // 🎵 添加轨道并启动混合器
        // 🔧 为什么：混合器需要知道编码格式才能创建MP4轨道
        trackIndex = muxer.addTrack(videoEncoder.getOutputFormat()); // 📞 将编码器输出格式作为新轨道添加到混合器
        if (!muxerStarted && trackIndex >= 0) { // 🔍 确保混合器未启动且轨道添加成功
          muxer.start(); // ▶️ 启动混合器，开始接收编码数据
          muxerStarted = true; // 📝 标记混合器已启动，允许writeSampleData写入
        }
      }
    }; // ✅ encoderCallback初始化完成
  } // ✅ onCreate结束

  // ▶️ Activity恢复回调（共7行，需逐行注释）
  // 🔧 为什么：检查外部存储写入权限，录制视频需要保存文件
  // 📍 时机：Activity从后台回到前台时由系统调用
  @Override
  protected void onResume() {
    // 📞 super.onResume(): 调用父类onResume
    // 💡 为什么调用：必须执行系统级恢复逻辑（恢复UI状态等）
    // 💡 作用：完成标准的Activity恢复流程
    // 💡 时机：自定义恢复逻辑前必须调用
    super.onResume(); // 📞 调用父类onResume，完成基础恢复逻辑
    // 🔐 PermissionHelper.hasWriteStoragePermission(this): 检查存储写入权限
    // 💡 为什么检查：录制的MP4文件需要保存到外部存储（Pictures/grafika/）
    // 💡 作用：判断当前Activity是否已获得WRITE_EXTERNAL_STORAGE权限
    // 💡 this变量作用：当前Activity上下文，用于查询权限状态
    // 💡 时机：Activity每次恢复时都需要检查（权限可能被用户在设置中撤销）
    if (!PermissionHelper.hasWriteStoragePermission(this)) { // ❌ 没有存储写入权限
      // 🙏 PermissionHelper.requestWriteStoragePermission(this): 请求存储写入权限
      // 💡 为什么请求：没有权限则无法保存录制文件，功能无法使用
      // 💡 作用：弹出系统权限请求对话框，让用户授权存储写入
      // 💡 时机：检测到权限缺失时立即请求
      PermissionHelper.requestWriteStoragePermission(this); // 🙏 请求存储写入权限
    }
  }

  // 🔐 处理存储权限请求结果（共9行，需逐行注释）
  // 🔧 为什么：处理用户对存储权限请求的响应，决定是否能保存录制文件
  // 📍 时机：用户从权限对话框返回后由系统调用
  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults); // 📞 调用父类处理
    // ❌ 权限被拒绝时提示并关闭
    // 🔍 为什么：存储权限是录制视频的必要条件，没有则无法保存文件
    // 💡 this变量作用：当前Activity上下文
    if (!PermissionHelper.hasWriteStoragePermission(this)) { // ❌ 仍然没有存储写入权限
      Toast.makeText(this, // 💬 显示权限被拒提示
              "Writing to external storage permission is needed to run this application", Toast.LENGTH_LONG).show(); // 📝 设置提示文本
      PermissionHelper.launchPermissionSettings(this); // ⚙️ 打开系统权限设置页面
      finish(); // 🚪 关闭Activity，因为无法保存录制文件
    }
  }

  // ▶️ 开始屏幕录制（共44行，需逐行注释）
  // 🔧 为什么：获取屏幕参数、准备编码器、创建混合器、启动虚拟显示
  // 📍 时机：用户授权屏幕录制权限后由onActivityResult调用
  @RequiresApi(api = Build.VERSION_CODES.M) // 🏷️ 声明需要API 23+
  private void startRecording() {
    // 📺 获取显示管理器和默认显示
    // 🔍 为什么：需要获取屏幕信息来配置虚拟显示和编码参数
    // 📺 DisplayManager dm: 显示管理器系统服务
    // 💡 为什么定义：需要获取屏幕信息来配置虚拟显示和编码参数
    // 💡 作用：管理所有显示设备，提供获取Display对象的接口
    // 💡 时机：startRecording()开始时获取，用于获取默认显示器
    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE); // 📞 获取DisplayManager系统服务
    // 🖥️ defaultDisplay: 默认显示器引用
    // 💡 为什么定义：需要获取主屏幕的分辨率和密度来设置编码参数
    // 💡 作用：持有主屏幕Display对象，后续通过它获取屏幕尺寸
    // 💡 时机：获取dm之后立即获取，用于传递屏幕信息给编码器
    Display defaultDisplay; // 🖥️ 声明默认显示器引用，用于获取屏幕信息
    // 🔍 检查DisplayManager服务是否可用
    if (dm != null) { // 🔍 检查服务是否可用
      // 📞 dm.getDisplay(Display.DEFAULT_DISPLAY): 获取默认显示器
      // 💡 为什么获取：虚拟显示需要基于主屏幕的信息进行配置
      // 💡 作用：返回主屏幕的Display对象，包含屏幕尺寸和旋转信息
      defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY); // 📞 获取默认显示器（通常是主屏幕）
    } else {
      // 💥 DisplayManager不可用，抛出异常
      // 💡 为什么抛出：DisplayManager是系统核心服务，不存在意味着系统异常
      throw new IllegalStateException("Cannot display manager?!?"); // 💥 服务不可用则抛出异常
    }
    // 🔍 检查默认显示器是否有效
    if (defaultDisplay == null) { // 🔍 再次检查显示器是否有效
      // 💥 没有可用显示器，抛出异常
      // 💡 为什么抛出：没有显示器就无法确定屏幕尺寸，编码器无法配置
      throw new RuntimeException("No display found."); // 💥 没有可用显示器则抛出异常
    }

    // Get the display size and density.
    // 📐 获取屏幕尺寸和密度
    // 🔍 为什么：编码器和虚拟显示需要正确的分辨率和DPI
    DisplayMetrics metrics = getResources().getDisplayMetrics(); // 📞 获取当前屏幕的DisplayMetrics对象
    int screenWidth = metrics.widthPixels;   // 📐 屏幕宽度（像素），用于设置编码分辨率
    int screenHeight = metrics.heightPixels; // 📐 屏幕高度（像素），用于设置编码分辨率
    int screenDensity = metrics.densityDpi;  // 📐 屏幕密度（DPI），用于设置虚拟显示质量

    // 🎬 准备视频编码器
    // 🔧 为什么：编码器必须在混合器之前准备好，因为混合器需要编码格式
    prepareVideoEncoder(screenWidth, screenHeight); // 📞 使用屏幕尺寸初始化H.264编码器

    try {
      // 📁 创建输出文件路径
      // 🔍 为什么：录制的MP4文件需要保存到外部存储
      File outputFile = new File(Environment.getExternalStoragePublicDirectory( // 📂 获取公共图片目录
              Environment.DIRECTORY_PICTURES) + "/grafika", "Screen-record-" + // 📁 在Pictures/grafika子目录下
              Long.toHexString(System.currentTimeMillis()) + ".mp4"); // 🏷️ 文件名使用当前时间戳的16进制，确保唯一
      if (!outputFile.getParentFile().exists()) { // 🔍 检查父目录是否存在
        outputFile.getParentFile().mkdirs(); // 📁 不存在则递归创建目录
      }
      // 🎬 创建媒体混合器
      // 🔧 为什么：MediaMuxer负责将编码后的H.264数据封装成MP4文件
      muxer = new MediaMuxer(outputFile.getCanonicalPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); // 📞 创建MPEG-4格式的混合器
    } catch (IOException ioe) { // ❌ 捕获IO异常（如存储空间不足）
      throw new RuntimeException("MediaMuxer creation failed", ioe); // 💥 混合器创建失败则抛出运行时异常
    }


    // Start the video input.
    // 🎥 创建虚拟显示并开始录制
    // 🔧 为什么：VirtualDisplay将屏幕内容投射到编码器的输入Surface上
    mediaProjection.createVirtualDisplay("Recording Display", // 🏷️ 虚拟显示名称
            screenWidth, // 📐 虚拟显示宽度（与屏幕一致）
            screenHeight, // 📐 虚拟显示高度（与屏幕一致）
            screenDensity, // 📐 虚拟显示密度（与屏幕一致）
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR/* flags */, // 🪞 自动镜像标志
            inputSurface, // 🖼️ 编码器的输入Surface，屏幕内容渲染到此处
            null /* callback */, // 📬 虚拟显示回调（本例不需要）
            null /* handler */); // 🧵 回调的Handler（本例不需要）
  } // ✅ startRecording结束

  // 🎬 准备视频编码器（共29行，接近30行阈值，完整注释）
  // 🔧 为什么：配置H.264编码器的参数，创建输入Surface供虚拟显示渲染
  // 📍 时机：startRecording中在创建混合器之前调用
  @RequiresApi(api = Build.VERSION_CODES.M) // 🏷️ 声明需要API 23+
  private void prepareVideoEncoder(int width, int height) { // 📐 width/height：编码分辨率，来自屏幕尺寸
    // 📝 创建视频格式
    // 🔍 为什么：MediaFormat定义了编码器的所有配置参数
    MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height); // 🎬 创建H.264视频格式对象
    int frameRate = 30; // ⏱️ 帧率30fps，每秒30帧（常规视频帧率）

    // Set some required properties. The media codec may fail if these aren't defined.
    // ⚙️ 设置编码器参数（必须属性）
    // 🔧 为什么：这些参数是MediaCodec编码器的必要配置，缺少会导致编码器创建失败
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, // 🎨 颜色格式
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); // 🖼️ 使用Surface作为输入（而非ByteBuffer）
    format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 📊 比特率6Mbps，影响视频清晰度和文件大小
    format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate); // ⏱️ 视频帧率30fps
    format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate); // 📸 捕获帧率30fps（与帧率一致）
    format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate); // 🔄 帧间隔微秒数（约33333μs），当无新帧时重复上一帧
    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1); // 🔊 声道数设为1（虽然本例无音频，但编码器可能需要此参数）
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 📐 I帧间隔1秒，每秒一个关键帧，影响seek和压缩效率

    // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
    // 🎬 创建MediaCodec编码器并配置，获取用于录制的Surface
    try {
      // 🎬 创建并配置编码器
      // 🔧 为什么：根据MIME类型创建H.264编码器实例
      videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE); // 📞 创建H.264编码器
      // ⚙️ configure(): 配置编码器
      // 💡 参数1 format：编码格式配置（上面设置的各种参数）
      // 💡 参数2 null：无输出Surface（用ByteBuffer直接从编码器取数据）
      // 💡 参数3 null：无加密器
      // 💡 参数4 CONFIGURE_FLAG_ENCODE：标记为编码模式
      // 💡 为什么配置：createEncoderByType()只创建编码器对象，必须configure才能使用
      videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // ⚙️ 配置为编码模式，无输出Surface（用ByteBuffer输出）
      // 🖼️ 创建输入Surface
      // 🔍 为什么：Surface模式允许直接渲染视频帧，无需手动填充像素数据
      inputSurface = videoEncoder.createInputSurface(); // 📞 创建编码器的输入Surface，虚拟显示将渲染到此处
      videoEncoder.setCallback(encoderCallback); // 📬 设置异步回调，编码输出通过callback处理
      videoEncoder.start(); // ▶️ 启动编码器，开始接受输入并输出编码数据
    } catch (IOException e) { // ❌ 编码器创建失败
      releaseEncoders(); // 🧹 释放已分配的资源
    }
  } // ✅ prepareVideoEncoder结束

  // 🧹 释放编码器资源（共28行，需逐行注释）
  // 🔧 为什么：停止录制并释放所有媒体资源，避免内存泄漏
  // 📍 时机：停止录制或Activity销毁时调用
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP) // 🏷️ 声明需要API 21+
  private void releaseEncoders() {
    // 🎬 停止并释放混合器
    // 🔍 为什么：MediaMuxer负责封装MP4文件，必须先停止再释放
    // 💡 muxer变量作用：媒体混合器，将编码数据写入MP4文件
    if (muxer != null) { // 🔍 检查混合器是否存在
      if (muxerStarted) { // 🔍 检查混合器是否已启动
        muxer.stop(); // ⏹️ 停止混合器，完成文件写入
      }
      muxer.release(); // 🗑️ 释放混合器资源
      muxer = null; // 🔄 清空引用，便于垃圾回收
      muxerStarted = false; // 🔄 重置启动标志
    }
    // 🎥 停止并释放视频编码器
    // 🔍 为什么：MediaCodec编码器占用硬件资源，必须及时释放
    // 💡 videoEncoder变量作用：H.264视频编码器
    if (videoEncoder != null) { // 🔍 检查编码器是否存在
      videoEncoder.stop(); // ⏹️ 停止编码器
      videoEncoder.release(); // 🗑️ 释放编码器资源
      videoEncoder = null; // 🔄 清空引用
    }
    // 🖼️ 释放输入Surface
    // 🔍 为什么：Surface占用图形资源，必须释放
    // 💡 inputSurface变量作用：编码器输入Surface，虚拟显示渲染目标
    if (inputSurface != null) { // 🔍 检查Surface是否存在
      inputSurface.release(); // 🗑️ 释放Surface资源
      inputSurface = null; // 🔄 清空引用
    }
    // 📺 停止媒体投影
    // 🔍 为什么：MediaProjection控制屏幕捕获，必须停止
    // 💡 mediaProjection变量作用：媒体投影，创建虚拟显示捕获屏幕
    if (mediaProjection != null) { // 🔍 检查投影是否存在
      mediaProjection.stop(); // ⏹️ 停止屏幕捕获
      mediaProjection = null; // 🔄 清空引用
    }
    // 🔄 重置轨道索引
    // 🔍 为什么：轨道索引标记混合器状态，必须重置为初始值
    // 💡 trackIndex变量作用：混合器轨道索引，-1表示未添加轨道
    trackIndex = -1; // 🔄 重置为初始值
  }

  // ⏹️ 停止屏幕录制
  @RequiresApi(api = Build.VERSION_CODES.M)
  private void stopRecording() {
    releaseEncoders();
  }

  // 🔐 处理权限请求结果（共19行，需逐行注释）
  // 🔧 为什么：处理屏幕录制权限请求结果，决定是否开始录制
  // 📍 时机：用户从屏幕录制权限对话框返回后由系统调用
  // 💡 REQUEST_CODE_CAPTURE_PERM变量：权限请求码，用于识别回调来源
  @RequiresApi(api = Build.VERSION_CODES.M) // 🏷️ 声明需要API 23+
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    // 🔍 检查是否为屏幕录制权限请求
    // 🔍 为什么：Activity可能有多个startActivityForResult，需要区分来源
    // 📌 requestCode变量：请求码，用于匹配对应的权限请求
    // 📍 作用：只有匹配REQUEST_CODE_CAPTURE_PERM时才处理屏幕录制逻辑
    // ⏰ 时机：回调触发时立即判断
    if (REQUEST_CODE_CAPTURE_PERM == requestCode) { // 🔀 匹配权限请求码
      // 🔘 b变量：录制按钮控件引用（Button类型）
      // 🔍 为什么获取：需要更新按钮状态（启用、修改文本）
      // 📍 作用：持有录制按钮引用，用于setEnabled和setText操作
      // ⏰ 时机：权限结果返回后立即获取
      Button b = findViewById(R.id.screen_record_button); // 🔍 获取录制按钮
      // 🔓 启用按钮：恢复用户交互能力
      // 🔍 为什么启用：在startActivityForResult前禁用了按钮，防止重复点击
      // 📍 作用：允许用户再次点击按钮（开始或停止录制）
      b.setEnabled(true); // 🔓 启用按钮，允许再次点击
      // ✅ 检查用户是否授权屏幕录制
      // 🔍 为什么：RESULT_OK表示用户点击了"立即开始"，授权了屏幕捕获
      // 📌 resultCode变量：权限结果码，RESULT_OK=授权，RESULT_CANCELED=拒绝
      // 📍 作用：决定是否获取MediaProjection并开始录制
      // ⏰ 时机：匹配requestCode后立即判断
      if (resultCode == RESULT_OK) { // ✅ 用户授权
        // ✅ 权限 granted 后开始录制
        // 🔍 为什么获取MediaProjection：需要它来创建VirtualDisplay捕获屏幕内容
        // 📌 mediaProjectionManager变量：媒体投影管理器系统服务
        // 📌 mediaProjection变量：媒体投影实例，用于创建虚拟显示
        // 📍 作用：将resultCode和intent传给getMediaProjection()获取投影实例
        // ⏰ 时机：用户授权后立即获取
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent); // 📞 获取MediaProjection
        // ▶️ startRecording(): 开始屏幕录制
        // 🔍 为什么调用：权限和MediaProjection都已就绪，可以开始录制
        // 📍 作用：准备编码器、创建混合器、启动虚拟显示捕获屏幕
        // ⏰ 时机：获取MediaProjection后立即调用
        startRecording(); // ▶️ 开始屏幕录制
        // ✍️ 更新按钮文本为"停止录制"
        // 🔍 为什么：提示用户当前正在录制，再次点击可停止
        // 📍 作用：提供视觉反馈，让用户知道录制状态
        b.setText(R.string.toggleRecordingOff); // ✍️ 更新按钮文本为"停止录制"
      } else { // ❌ 用户拒绝授权
        // ❌ 权限被拒绝时提示
        // 🔍 为什么提示：屏幕录制需要用户明确授权，拒绝则功能无法使用
        // 📍 作用：告知用户为什么需要此权限，引导其授予权限
        new AlertDialog.Builder(this) // 💬 创建错误提示对话框
                .setTitle("Error") // 🏷️ 设置对话框标题
                .setMessage("Permission is required to record the screen.") // 📝 设置错误信息
                .setNeutralButton(android.R.string.ok, null) // 🔘 设置确定按钮
                .show(); // 👁️ 显示对话框
      }
    }
  }
}
