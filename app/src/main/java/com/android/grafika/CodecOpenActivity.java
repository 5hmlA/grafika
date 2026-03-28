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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import com.google.grafika.R;

/**
 * 🧪 打开大量 MediaCodec 编码器，观察系统行为。
 * Opens a large number of MediaCodec encoders, just to see what happens.
 * <p>
 * We never explicitly release the instances, though they will get garbage collected
 * eventually.  The activity provides a "GC" button (so you can force the GC to happen)
 * and a "Halt" button (which kills the app so you can see if mediaserver is cleaning up).
 * 从不显式释放实例，最终会被垃圾回收。
 * 提供"GC"按钮（强制垃圾回收）和"Halt"按钮（终止进程，观察 mediaserver 是否清理）。
 */
public class CodecOpenActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private static final int MAX_OPEN = 256;  // 🔢 最大打开编码器数量

    /**
     * 🔧 Activity创建时调用（共11行，需逐行注释）
     * 💡 作用：初始化布局，准备测试MediaCodec的UI界面
     * 💡 时机：Activity首次创建时由系统调用
     */
    // 🚀 Activity 创建时初始化布局
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📦 savedInstanceState: Activity之前保存的状态数据
        // 💡 为什么传入：系统在Activity被回收后重建时，会传入之前onSaveInstanceState保存的数据
        // 💡 作用：恢复Activity之前的状态（如旋转屏幕后恢复数据）
        // 💡 时机：onCreate被系统调用时自动传入
        // 💡 注意：首次创建时为null，重建时为非null
        // 🔄 super.onCreate(savedInstanceState): 调用父类onCreate
        // 💡 为什么调用：必须执行系统级Activity创建流程
        // 💡 作用：恢复之前保存的Activity状态，初始化内部组件
        // 💡 时机：方法入口处必须首先调用
        super.onCreate(savedInstanceState);

        // 🖥️ setContentView(): 加载布局文件activity_codec_open.xml
        // 💡 为什么调用：需要将XML布局渲染到屏幕
        // 💡 作用：将XML中定义的View（按钮等）显示到屏幕上
        // 💡 时机：父类onCreate之后立即调用
        // 💡 R.layout.activity_codec_open: 布局文件资源ID
        setContentView(R.layout.activity_codec_open);
    }

    /**
     * onClick handler for "start" button.
     * We create as many codecs as we can and return without releasing them.  The codecs
     * will remain in use until the next GC.
     * 🎯 "开始"按钮点击处理方法
     * 💡 功能：尽可能多地创建MediaCodec编码器实例，测试系统资源上限
     * 💡 设计：故意不释放编码器，观察GC行为和mediaserver清理能力
     *
     * @param unused 视图参数（未使用，XML onClick需要此参数）
     */
    public void clickStart(@SuppressWarnings("unused") View unused) {
        // 📝 MIME_TYPE: 编码所需的MIME类型常量
        //    为什么定义：MediaCodec.createEncoderByType()需要此参数来创建特定格式的编码器
        //    作用：指定视频编码格式为H.264 (AVC)，这是最广泛支持的格式
        //    使用时机：创建编码器和MediaFormat时传入
        final String MIME_TYPE = "video/avc";        // 🎬 H.264编码格式

        // 📝 WIDTH: 视频宽度（像素）
        //    为什么定义：MediaFormat需要知道编码输出的视频尺寸
        //    作用：设置编码输出的视频宽度，320是较低分辨率，减少资源占用
        //    使用时机：创建MediaFormat和后续编码配置
        final int WIDTH = 320;                       // 📐 宽度

        // 📝 HEIGHT: 视频高度（像素）
        //    为什么定义：与WIDTH配合定义完整的视频分辨率
        //    作用：设置编码输出的视频高度，240是较低分辨率
        //    使用时机：创建MediaFormat时传入
        final int HEIGHT = 240;                      // 📐 高度

        // 📝 BIT_RATE: 编码比特率（bits/s）
        //    为什么定义：控制编码输出的质量和文件大小平衡
        //    作用：1Mbps为较低质量但仍可接受的码率，适合测试
        //    使用时机：配置MediaFormat KEY_BIT_RATE参数
        final int BIT_RATE = 1000000;                // 📊 比特率1Mbps

        // 📝 FRAME_RATE: 目标帧率
        //    为什么定义：告知编码器期望的输入帧率，影响码率分配
        //    作用：15fps是较低帧率，减少编码负担
        //    使用时机：配置MediaFormat KEY_FRAME_RATE参数
        final int FRAME_RATE = 15;                   // 🎞️ 帧率15fps

        // 📝 IFRAME_INTERVAL: I帧（关键帧）间隔（秒）
        //    为什么定义：控制关键帧插入频率，影响随机访问能力和压缩效率
        //    作用：每隔1秒插入一个完整的关键帧，便于seek和错误恢复
        //    使用时机：配置MediaFormat KEY_I_FRAME_INTERVAL参数
        final int IFRAME_INTERVAL = 1;               // ⏱️ I帧间隔1秒

        // 📝 START_CODEC: 是否启动编码器的标志
        //    为什么定义：控制创建编码器后是否立即启动（start），测试不同场景
        //    作用：true时创建Surface并启动编码器，false时仅创建不启动
        //    使用时机：在创建编码器循环中判断是否调用createInputSurface和start
        final boolean START_CODEC = true;            // ▶️ 是否启动编码器

        // 📝 format: 视频MediaFormat对象
        //    为什么定义：MediaCodec.configure()需要MediaFormat参数来配置编码器
        //    作用：封装编码器所需的配置参数集合（MIME类型、尺寸等）
        //    使用时机：后续设置颜色格式、比特率等参数，并传给编码器configure
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);

        // ⚙️ 设置编码参数
        // 📝 KEY_COLOR_FORMAT: 颜色格式设置为Surface模式
        //    为什么设置：告知编码器从Surface输入帧数据（零拷贝，高效）
        //    作用：启用硬件加速编码，避免CPU到GPU的数据拷贝
        //    使用时机：编码器configure时读取此参数
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        // 📝 KEY_BIT_RATE: 设置比特率
        //    为什么设置：编码器根据此值分配压缩质量
        //    作用：值越高质量越好但文件越大，1Mbps适合测试
        //    使用时机：编码过程中动态调节码率
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);

        // 📝 KEY_FRAME_RATE: 设置帧率
        //    为什么设置：影响编码器的帧间预测和码率控制
        //    作用：15fps适合测试，减少编码负担
        //    使用时机：编码器内部帧率估计参考
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);

        // 📝 KEY_I_FRAME_INTERVAL: 设置I帧间隔
        //    为什么设置：控制关键帧插入频率
        //    作用：1秒间隔平衡了seek能力和压缩效率
        //    使用时机：编码器决定何时编码完整帧而非差分帧
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        // 📝 打印格式信息
        //    为什么记录：方便调试确认参数设置正确
        //    使用时机：参数配置完成后记录
        Log.d(TAG, "format: " + format);

        // 📝 codecs: 编码器数组，容量为MAX_OPEN（256）
        //    为什么定义：需要存储所有创建的编码器实例，循环结束后用于统计
        //    作用：for循环中逐个赋值，循环结束后数组内容反映成功数量
        //    使用时机：for循环中逐个创建并赋值
        MediaCodec[] codecs = new MediaCodec[MAX_OPEN];  // 📦 编码器数组

        // 📝 i: 循环计数器，记录当前创建到第几个编码器
        //    为什么定义：需要记录成功创建的编码器总数
        //    作用：循环控制+最终记录成功创建的编码器总数
        //    使用时机：for循环递增；循环结束传给showCountDialog显示
        int i;

        // 📝 for循环：循环创建编码器，直到达到上限或系统资源耗尽
        //    为什么用for：需要尝试创建尽可能多的编码器，测试系统资源上限
        //    退出条件：达到MAX_OPEN(256)或创建失败（资源耗尽）
        for (i = 0; i < MAX_OPEN; i++) {
            // 📝 try块：捕获创建/配置/启动编码器时可能发生的异常
            //    为什么用try：编码器创建可能因资源耗尽而失败，需要优雅处理
            //    使用时机：每次循环迭代时执行
            try {
                // 📝 createEncoderByType(): 根据MIME类型创建H.264编码器实例
                //    为什么调用：需要申请系统MediaCodec资源
                //    作用：创建一个编码器对象，但还未配置和启动
                //    使用时机：每次循环迭代时调用，失败则catch跳出
                codecs[i] = MediaCodec.createEncoderByType(MIME_TYPE);   // 🔧 创建编码器

                // 📝 configure(): 配置编码器
                //    参数1 format：编码格式配置（上面设置的各种参数）
                //    参数2 null：无输出Surface（直接从编码器取数据）
                //    参数3 null：无加密器
                //    参数4 CONFIGURE_FLAG_ENCODE：标记为编码模式
                //    为什么调用：创建编码器后必须配置才能使用
                //    作用：初始化编码器内部状态，准备编码参数
                //    使用时机：createEncoderByType()之后、start()之前必须调用
                codecs[i].configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);  // ⚙️ 配置

                // 📝 判断是否需要启动编码器
                //    为什么判断：START_CODEC=false时仅测试创建和配置，不启动
                //    作用：控制测试范围，可以分别测试创建/配置/启动不同阶段
                //    使用时机：仅当编码器configure成功后才进入此分支
                if (START_CODEC) {
                    // 📝 createInputSurface(): 创建输入Surface
                    //    为什么调用：需要一个可绘制的目标，应用可以通过它提交视频帧
                    //    作用：返回一个Surface对象，绑定到编码器输入端
                    //    使用时机：在start()之前调用，之后可以通过Surface绘制帧
                    codecs[i].createInputSurface();  // 🖼️ 创建输入表面

                    // 📝 start(): 启动编码器
                    //    为什么调用：使编码器进入运行状态，开始处理输入帧
                    //    作用：初始化编码器内部线程，准备接收输入并产生编码输出
                    //    使用时机：configure+createInputSurface之后调用
                    codecs[i].start();               // ▶️ 启动编码器
                }
            } catch (Exception ex) {
                // 📝 catch块：捕获异常（编码器创建/配置/启动失败）
                //    为什么捕获：系统无法再分配新编码器时会抛出异常
                //    作用：记录失败信息并跳出循环（资源耗尽或系统限制）
                //    使用时机：系统资源耗尽时触发
                Log.i(TAG, "Failed on creation of codec #" + i, ex);  // 🚨 创建失败
                break;
            }
        }

        // 📝 showCountDialog(): 显示成功创建的编码器数量对话框
        //    为什么调用：需要告知用户测试结果（成功创建了多少个）
        //    作用：弹出对话框显示测试结果
        //    使用时机：循环结束后立即调用
        showCountDialog(i);  // 📊 显示成功创建数量
    }

    /**
     * Puts up a dialog showing how many codecs we created.
     * 💬 显示成功创建编码器数量的对话框。
     * 💡 作用：向用户展示压力测试的结果
     * 💡 时机：clickStart()循环结束后调用
     *
     * @param count 成功创建的编码器数量
     */
    private void showCountDialog(int count) {
        // 🏗️ builder变量：AlertDialog构建器（AlertDialog.Builder类型）
        // 🔍 为什么定义：AlertDialog需要通过Builder模式构建，不能直接new
        // 📍 作用：链式配置对话框的标题、消息、按钮等属性
        // ⏰ 时机：测试结果出来后立即创建，用于显示结果
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // 📝 setTitle(): 设置对话框标题
        // 🔍 为什么调用：对话框需要标题来说明用途
        // 📍 作用：从strings.xml获取标题文字（如"编码器数量"）
        // ⏰ 时机：构建器创建后第一个设置
        builder.setTitle(R.string.codecOpenCountTitle);

        // 📝 msg变量：要显示的完整消息文本（String类型）
        // 🔍 为什么定义：需要将编码器数量嵌入消息模板
        // 📍 作用：将count值插入消息模板，如"成功创建了N个编码器"
        // ⏰ 时机：设置标题后格式化消息，传给setMessage()
        // 📌 count参数：成功创建的编码器数量，替换模板中的%d占位符
        String msg = getString(R.string.codecOpenCountMsg, count);

        // 📝 setMessage(): 设置对话框消息内容
        // 🔍 为什么调用：需要显示测试结果的具体数值
        // 📍 作用：将格式化后的消息显示在对话框主体区域
        // ⏰ 时机：消息字符串准备好后设置
        builder.setMessage(msg);

        // 🔘 setPositiveButton(): 设置确定按钮及其点击监听器
        // 🔍 为什么调用：用户需要一种方式关闭对话框
        // 📍 作用：用户点击"确定"时关闭对话框并释放资源
        // 📌 参数1 R.string.ok：按钮文字（如"确定"）
        // 📌 参数2 OnClickListener：点击回调接口实现
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                // 🚪 dialog.dismiss(): 关闭对话框
                // 🔍 为什么调用：对话框显示时占用窗口资源，需要及时释放
                // 📍 作用：移除对话框视图，恢复Activity交互
                // ⏰ 时机：用户点击"确定"按钮时调用
                dialog.dismiss();
            }
        });

        // 🏗️ dialog变量：最终创建的AlertDialog实例
        // 🔍 为什么定义：Builder只是配置，需要调用create()才能生成可显示的对话框
        // 📍 作用：持有对话框对象，后续调用show()将其呈现在屏幕上
        // ⏰ 时机：所有配置完成后创建
        AlertDialog dialog = builder.create();

        // 📺 show(): 显示对话框给用户
        // 🔍 为什么调用：对话框创建后默认不可见，需要调用show()才能显示
        // 📍 作用：将对话框呈现在屏幕最上层，覆盖当前Activity
        // ⏰ 时机：对话框创建后立即显示
        dialog.show();
    }

    /**
     * onClick handler for "GC" button.
     * <p>
     * Initiates manual garbage collection.  Some of the native stuff might not get cleaned up
     * until finalizers run, so we request those too.
     * 🧹 "GC"按钮点击处理。手动触发垃圾回收和终结器。
     * 💡 作用：强制GC释放未引用的MediaCodec实例，观察mediaserver清理行为
     * 💡 时机：用户点击"GC"按钮时调用
     *
     * @param unused 视图参数（未使用，XML onClick需要此参数）
     */
    public void clickGc(@SuppressWarnings("unused") View unused) {
        // 📝 Log.i(): 记录开始垃圾回收的日志
        //    为什么记录：方便追踪GC触发时机和后续资源释放情况
        //    作用：在logcat中标记GC操作时间点
        //    使用时机：GC操作前最先记录
        Log.i(TAG, "Collecting garbage");

        // 🗑️ System.gc(): 第一次触发GC（标记阶段）
        //    为什么调用：需要释放未引用的MediaCodec实例
        //    作用：遍历对象图，标记可达对象，将不可达对象加入待回收列表
        //    使用时机：用户点击GC按钮后立即调用
        //    注意：这只是建议GC，JVM可能忽略此调用
        System.gc();

        // 🔄 System.runFinalization(): 运行所有待终结对象的finalize()方法
        //    为什么调用：持有native资源的对象（如MediaCodec）需要在finalize()中释放底层资源
        //    作用：调用那些覆写了finalize()且不可达对象的清理方法
        //    机制：确保native资源在第二次GC前被释放
        //    使用时机：第一次GC后调用，让不可达对象有机会清理native资源
        System.runFinalization();

        // 🗑️ System.gc(): 第二次触发GC（回收阶段）
        //    为什么需要第二次：第一次GC标记的对象需要finalize()后才能真正回收
        //    作用：回收经过finalize()后彻底变为不可达的对象
        //    原因：finalize()可能重新引用对象，需要再次确认不可达
        //    使用时机：finalize()执行后调用，完成最终清理
        System.gc();
    }

    /**
     * onClick handler for "halt" button.
     * <p>
     * This kills the process, which will be immediately restarted.
     * 💀 "停止"按钮点击处理。立即终止进程（会被系统重启）。
     * 💡 作用：模拟进程崩溃，观察mediaserver是否正确清理native资源
     * 💡 时机：用户点击"Halt"按钮时调用
     *
     * @param unused 视图参数（未使用，XML onClick需要此参数）
     */
    public void clickHalt(@SuppressWarnings("unused") View unused) {
        // ⚠️ Log.w(): 打印警告日志
        //    为什么用w级别：halt是危险操作，需要醒目记录
        //    作用：在系统日志中留下记录，方便排查进程被强制终止的情况
        //    使用时机：halt操作前最先记录
        Log.w(TAG, "HALTING VM");

        // 💀 Runtime.getRuntime().halt(1): 强制终止JVM进程
        //    为什么调用：测试进程被杀死后，mediaserver是否正确清理编码器资源
        //    作用：立即终止应用进程，不执行任何关闭钩子或finalizer
        //    参数1：退出状态码，非零值表示异常终止
        //    与System.exit()区别：halt不运行关闭钩子，更快速但不优雅
        //    使用时机：用户点击"Halt"按钮时调用
        //    注意：进程会被系统立即重启（Android会重启被杀死的Activity所在进程）
        Runtime.getRuntime().halt(1);
    }
}
