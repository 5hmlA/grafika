package com.android.grafika;

import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.app.Activity;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView.OnItemSelectedListener;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.WindowSurface;
import java.lang.ref.WeakReference;
import com.google.grafika.R;

/**
 * Exercises a SurfaceFlinger feature that defers acquisition of a buffer until a
 * certain time.  The purpose of the feature is to make A/V sync easier by allowing
 * an app running at normal priority to schedule multiple frames with SurfaceFlinger
 * (which runs at elevated priority) well ahead of time.
 * <p>
 * Requires API 19 (Android 4.4 "KitKat").  In previous releases, frames are shown as
 * soon as possible.
 * 
 * 🎬 测试SurfaceFlinger的定时缓冲区获取功能
 * 💡 目的是让音视频同步更容易，允许应用提前调度多帧
 * 💡 需要Android 4.4 (KitKat)及以上版本
 */
public class ScheduledSwapActivity extends Activity implements OnItemSelectedListener,
        SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final String TAG = MainActivity.TAG;

    private final static long ONE_MILLISECOND_NS = 1000000;  // ⏱️ 一毫秒（纳秒）

    /**
     * Frame update patterns.  Each digit represents the number of times a given source
     * frame will be repeated.
     * 
     * 🎬 帧更新模式，每个数字表示源帧重复的次数
     * 💡 例如"32"表示24fps（60Hz显示器上使用3-2下拉模式）
     */
    private static final String[] UPDATE_PATTERNS = {
        "4",        // 15 fps
        "32",       // 24 fps
        "32322",    // 25 fps
        "2",        // 30 fps
        "2111",     // 48 fps
        "1",        // 60 fps
        "15"        // erratic, useful for examination with systrace
    };

    /**
     * How far ahead of time we schedule frames.
     * 
     * ⏱️ 提前调度帧的数量
     * 💡 N=2是安全的，N=0是不可能的，N=3会导致在60Hz提交时阻塞
     */
    private static final int[] FRAME_AHEAD = { 0, 1, 2, 3 };

    // 🧵 渲染线程
    private RenderThread mRenderThread;
    private long mRefreshPeriodNs;              // ⏱️ 显示刷新周期
    private int mUpdatePatternIndex = 1;        // 📊 更新模式索引（24fps）
    private int mFramesAheadIndex = 2;          // ⏱️ 提前帧数索引（+2）

    /**
     * 🔧 Activity创建时调用
     * 初始化下拉列表和渲染线程
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 记录Activity创建日志
        // 作用：便于调试生命周期流程
        // 使用时机：方法入口立即打印
        Log.d(TAG, "onCreate");

        // 📝 调用父类onCreate
        // 作用：执行系统级Activity初始化（恢复状态等）
        // 使用时机：自定义初始化前必须调用
        super.onCreate(savedInstanceState);

        // 📝 设置布局文件
        // 作用：加载XML布局，建立View层级关系
        // 使用时机：onCreate中最先调用（在findViewById之前）
        setContentView(R.layout.activity_scheduled_swap);

        // 📋 更新速率下拉列表
        // 📝 获取"更新速率" Spinner 控件
        // 作用：通过ID找到界面中的下拉列表，用于选择帧率模式
        // 使用时机：布局加载后立即获取
        Spinner spinner = (Spinner) findViewById(R.id.scheduledSwapUpdate_spinner);

        // 📝 创建数组适配器（从资源文件读取选项列表）
        // 作用：将 strings.xml 中的帧率名称列表绑定到 Spinner
        // 使用时机：Spinner 初始化时创建，后续绑定到控件
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.scheduledSwapUpdateNames, android.R.layout.simple_spinner_item);

        // 📝 设置下拉列表样式
        // 作用：指定展开后的下拉菜单使用系统标准样式
        // 使用时机：适配器创建后立即设置
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 📝 将适配器绑定到 Spinner
        // 作用：让 Spinner 显示帧率选项列表
        // 使用时机：适配器配置完成后绑定
        spinner.setAdapter(adapter);

        // 📝 设置默认选中项
        // 作用：让 Spinner 初始显示之前保存的帧率模式（默认24fps）
        // 使用时机：适配器绑定后设置初始值
        spinner.setSelection(mUpdatePatternIndex);

        // 📝 注册选择事件监听器
        // 作用：当用户选择不同帧率时触发 onItemSelected 回调
        // 使用时机：Spinner 配置完成后注册
        spinner.setOnItemSelectedListener(this);

        // 📋 提前帧数下拉列表
        // 📝 获取"提前帧数" Spinner 控件
        // 作用：复用 spinner 变量，指向第二个下拉列表
        // 使用时机：第一个 Spinner 配置完成后获取第二个
        spinner = (Spinner) findViewById(R.id.scheduledSwapAhead_spinner);

        // 📝 创建数组适配器（提前帧数选项）
        // 作用：将帧数选项列表（0/1/2/3）绑定到第二个 Spinner
        // 使用时机：第二个 Spinner 初始化时创建
        adapter = ArrayAdapter.createFromResource(this,
                R.array.scheduledSwapAheadNames, android.R.layout.simple_spinner_item);

        // 📝 设置下拉列表样式（同第一个 Spinner）
        // 作用：保持UI风格一致
        // 使用时机：适配器创建后立即设置
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 📝 将适配器绑定到第二个 Spinner
        // 作用：让帧数选择器显示选项列表
        // 使用时机：适配器配置完成后绑定
        spinner.setAdapter(adapter);

        // 📝 设置默认选中项（+2帧）
        // 作用：让 Spinner 初始显示之前保存的提前帧数
        // 使用时机：适配器绑定后设置初始值
        spinner.setSelection(mFramesAheadIndex);

        // 📝 注册选择事件监听器
        // 作用：当用户选择不同帧数时触发回调
        // 使用时机：Spinner 配置完成后注册
        spinner.setOnItemSelectedListener(this);

        // 📊 查询显示器刷新率
        // 📝 获取显示器刷新周期（纳秒）
        // 作用：用于计算帧调度时间戳，决定帧何时呈现
        // 使用时机：Activity创建时查询一次，后续渲染使用
        mRefreshPeriodNs = MiscUtils.getDisplayRefreshNsec(this);

        // 📝 更新UI控件显示（初始丢帧数为0）
        // 作用：初始化状态文本，显示刷新率和丢帧统计
        // 使用时机：布局加载完成后首次更新
        updateControls(0);

        // 📝 获取SurfaceView并注册回调
        // 作用：监听Surface的创建/变化/销毁事件
        // 使用时机：Activity创建时注册，后续由系统回调
        SurfaceView sv = (SurfaceView) findViewById(R.id.scheduledSwap_surfaceView);
        sv.getHolder().addCallback(this);
    }

    /**
     * ⏸️ Activity暂停时调用
     * 移除Choreographer回调
     */
    @Override
    protected void onPause() {
        // 📝 调用父类onPause
        // 作用：执行系统级暂停逻辑
        // 使用时机：自定义暂停逻辑前必须调用
        super.onPause();

        // 📝 记录暂停日志
        // 作用：便于调试生命周期流程
        // 使用时机：暂停时打印，确认回调正常触发
        Log.d(TAG, "onPause unhooking choreographer");

        // 📝 移除帧回调
        // 作用：暂停时不再接收 vsync 事件，避免后台渲染浪费资源
        // 使用时机：Activity 暂停时立即移除
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /**
     * 🔄 Activity恢复时调用
     * 重新注册Choreographer回调
     */
    @Override
    protected void onResume() {
        // 📝 调用父类onResume
        // 作用：执行系统级恢复逻辑
        // 使用时机：自定义恢复逻辑前必须调用
        super.onResume();

        // 📝 检查渲染线程是否存在
        // 作用：只有渲染线程已创建才重新注册回调（避免空指针）
        // 使用时机：Surface 可能还未创建，需要安全判断
        if (mRenderThread != null) {
            // 📝 记录恢复日志
            // 作用：便于调试生命周期流程
            // 使用时机：确认渲染线程存在后打印
            Log.d(TAG, "onResume re-hooking choreographer");

            // 📝 重新注册帧回调
            // 作用：恢复时重新接收 vsync 事件，继续渲染
            // 使用时机：确认渲染线程可用后注册
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /**
     * 🖼️ Surface创建时调用
     * 创建渲染线程并启动
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 记录Surface创建日志
        // 作用：调试Surface生命周期，确认回调正常触发
        // 使用时机：方法入口立即打印
        Log.d(TAG, "surfaceCreated holder=" + holder);

        // 📝 获取SurfaceView的SurfaceHolder
        // 作用：将Surface传递给渲染线程进行GL绘制
        // 使用时机：创建渲染线程时传入
        SurfaceView sv = (SurfaceView) findViewById(R.id.scheduledSwap_surfaceView);

        // 📝 创建渲染线程实例
        // 作用：将SurfaceHolder和Activity引用传入，用于GL渲染
        // 使用时机：Surface首次创建时新建线程
        mRenderThread = new RenderThread(sv.getHolder(), this);

        // 📝 设置线程名称
        // 作用：便于在调试工具（如 systrace）中识别线程
        // 使用时机：线程创建后、启动前设置
        mRenderThread.setName("ScheduledSwap GL render");

        // 📝 启动渲染线程
        // 作用：开始执行 RenderThread.run() 方法
        // 使用时机：线程配置完成后启动
        mRenderThread.start();

        // 📝 等待渲染线程就绪
        // 作用：阻塞UI线程直到渲染线程完成 Looper 初始化
        // 使用时机：线程启动后立即等待，确保Handler可用
        mRenderThread.waitUntilReady();

        // 📝 获取渲染线程的Handler
        // 作用：用于UI线程向渲染线程发送消息
        // 使用时机：渲染线程就绪后获取
        RenderHandler rh = mRenderThread.getHandler();

        // 📝 检查Handler是否有效
        // 作用：防御性检查，避免空指针异常
        // 使用时机：获取Handler后立即判断
        if (rh != null) {
            // 📝 发送参数设置消息
            // 作用：将当前帧率模式和提前帧数传给渲染线程
            // 使用时机：渲染线程初始化后立即发送
            rh.sendSetParameters(mUpdatePatternIndex, mFramesAheadIndex);

            // 📝 发送Surface创建消息
            // 作用：通知渲染线程初始化GL环境（创建WindowSurface等）
            // 使用时机：参数设置后发送，确保GL上下文正确配置
            rh.sendSurfaceCreated();
        }

        // 📝 注册帧回调
        // 作用：开始接收 vsync 事件，触发渲染循环
        // 使用时机：渲染线程初始化完成后注册
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 记录Surface变化日志
        // 作用：调试Surface尺寸变化，确认回调正常触发
        // 使用时机：方法入口立即打印格式和尺寸
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height);

        // 📝 获取渲染线程的Handler
        // 作用：用于向渲染线程发送尺寸变化消息
        // 使用时机：Surface变化时获取
        RenderHandler rh = mRenderThread.getHandler();

        // 📝 检查Handler是否有效
        // 作用：防御性检查，避免空指针异常
        // 使用时机：获取Handler后立即判断
        if (rh != null) {
            // 📝 发送Surface变化消息
            // 作用：通知渲染线程更新视口尺寸和方块参数
            // 使用时机：确认Handler有效后发送
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    /**
     * 🗑️ Surface销毁时调用
     * 等待渲染线程关闭
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 记录Surface销毁日志
        // 作用：调试Surface生命周期，确认回调正常触发
        // 使用时机：方法入口立即打印
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // 📝 获取渲染线程的Handler
        // 作用：用于向渲染线程发送关闭消息
        // 使用时机：Surface销毁时获取
        RenderHandler rh = mRenderThread.getHandler();

        // 📝 检查Handler是否有效
        // 作用：防御性检查，避免空指针异常
        // 使用时机：获取Handler后立即判断
        if (rh != null) {
            // 📝 发送关闭消息
            // 作用：通知渲染线程退出 Looper 循环，释放GL资源
            // 使用时机：Surface销毁时发送
            rh.sendShutdown();

            // 📝 等待渲染线程结束
            // 作用：阻塞UI线程直到渲染线程完全退出，确保资源释放完成
            // 使用时机：发送关闭消息后等待
            try {
                // 📝 调用join阻塞等待
                // 作用：当前线程（UI线程）挂起，直到渲染线程的run()方法返回
                // 使用时机：发送shutdown消息后立即等待
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // 📝 处理中断异常
                // 作用：join被中断属于异常情况，包装为RuntimeException抛出
                // 使用时机：等待过程中线程被中断时触发
                throw new RuntimeException("join was interrupted", ie);
            }
        }

        // 📝 清空渲染线程引用
        // 作用：释放引用，允许GC回收渲染线程对象
        // 使用时机：渲染线程完全结束后清空
        mRenderThread = null;

        // 📝 记录销毁完成日志
        // 作用：确认所有清理操作已完成
        // 使用时机：方法结束前打印
        Log.d(TAG, "surfaceDestroyed complete");
    }

    /**
     * 🎯 Choreographer回调，在vsync附近调用
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        // 📝 获取渲染线程的Handler
        // 作用：用于向渲染线程发送帧绘制消息
        // 使用时机：vsync回调触发时获取
        RenderHandler rh = mRenderThread.getHandler();

        // 📝 检查Handler是否有效
        // 作用：防御性检查，避免空指针异常
        // 使用时机：获取Handler后立即判断
        if (rh != null) {
            // 📝 重新注册帧回调
            // 作用：确保下一帧 vsync 继续触发此回调（单次注册机制）
            // 使用时机：处理当前帧前注册下一帧
            Choreographer.getInstance().postFrameCallback(this);

            // 📝 发送帧绘制消息
            // 作用：将 vsync 时间戳传给渲染线程，触发帧绘制逻辑
            // 使用时机：注册下一帧回调后发送当前帧
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * 🎯 下拉列表选择事件
     * 💡 处理"更新速率"和"提前帧数"两个 Spinner 的选择变化
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        // 📝 获取触发事件的 Spinner 控件
        // 作用：通过类型转换获取 Spinner 对象，以便读取选中位置
        // 使用时机：事件回调触发时立即获取
        Spinner spinner = (Spinner) parent;

        // 📝 获取当前选中项的索引位置
        // 作用：用于和旧值比较，判断是否真的发生了变化
        // 使用时机：获取 spinner 后立即读取，后续与成员变量对比
        final int selIndex = spinner.getSelectedItemPosition();

        // 📝 标记参数是否发生更新
        // 作用：只有值确实改变了才通知渲染线程，避免无意义的消息
        // 使用时机：分支判断后设置，最后决定是否发送参数变更消息
        boolean updated = false;

        // 📝 判断是哪个 Spinner 触发了事件
        // 作用：根据 View ID 区分"更新速率"和"提前帧数"两个下拉列表
        // 使用时机：回调触发后立即判断
        if (parent.getId() == R.id.scheduledSwapUpdate_spinner) {
            // 📝 更新速率 Spinner 被选中
            // 作用：检查新值是否与旧值不同
            // 使用时机：更新速率 Spinner 的选择事件
            if (mUpdatePatternIndex != selIndex) {
                Log.d(TAG, "onItemSelected [update-rate]: " + selIndex);
                // 📝 更新成员变量记录新的模式索引
                // 作用：保存当前帧率模式，供后续渲染使用
                // 使用时机：值确实发生变化时更新
                mUpdatePatternIndex = selIndex;
                updated = true;
            }
        } else if (parent.getId() == R.id.scheduledSwapAhead_spinner) {
            // 📝 提前帧数 Spinner 被选中
            // 作用：检查新值是否与旧值不同
            // 使用时机：提前帧数 Spinner 的选择事件
            if (mFramesAheadIndex != selIndex) {
                Log.d(TAG, "onItemSelected [frames-ahead]: " + selIndex);
                // 📝 更新成员变量记录新的提前帧数索引
                // 作用：保存当前提前调度帧数，供渲染线程计算呈现时间戳
                // 使用时机：值确实发生变化时更新
                mFramesAheadIndex = selIndex;
                updated = true;
            }
        } else {
            // 📝 未知的 Spinner，属于开发错误，直接抛异常
            // 作用：防止遗漏未处理的 Spinner，辅助调试
            // 使用时机：未匹配到已知 ID 时触发
            throw new RuntimeException("Unknown spinner");
        }

        // 📝 判断是否需要通知渲染线程
        // 作用：渲染线程可能尚未创建（Activity 暂停中），需要安全处理
        // 使用时机：参数检查完成后统一处理通知逻辑
        if (mRenderThread == null) {
            // 📝 渲染线程为空，说明 Activity 处于暂停状态
            // 作用：记录日志，不做任何通知（参数会在 surfaceCreated 时传递）
            // 使用时机：用户在暂停状态下修改 Spinner 选择
            Log.d(TAG, "In onItemSelected while the activity is paused");
        } else if (updated) {
            // 📝 参数发生了变化且渲染线程可用，发送参数更新消息
            // 作用：通过 Handler 通知渲染线程应用新参数
            // 使用时机：确认变化且渲染线程存活时
            RenderHandler rh = mRenderThread.getHandler();
            if (rh != null) {
                // 📝 通过 Handler 发送参数设置消息
                // 作用：将新模式索引和提前帧数索引传给渲染线程
                // 使用时机：渲染线程 Handler 有效时立即发送
                rh.sendSetParameters(mUpdatePatternIndex, mFramesAheadIndex);
            }
        }
    }

    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * 🔄 更新UI元素
     */
    private void updateControls(int droppedFrames) {
        // 📝 str - 格式化后的状态字符串
        // 💡 为什么定义：需要将丢帧数格式化为可读的文本显示在界面上
        // 💡 作用：存储格式化后的字符串，用于设置到TextView
        // ⏰ 使用时机：获取控件后设置文本时使用
        // 💡 getString()的第二个参数会替换字符串模板中的占位符
        String str = getString(R.string.scheduledSwapStatus, droppedFrames);

        // 📝 tv - 状态TextView控件的引用
        // 💡 为什么定义：需要获取控件引用来设置显示文本
        // 💡 作用：存储TextView引用，用于显示丢帧统计信息
        // ⏰ 使用时机：获取后立即设置文本
        TextView tv = (TextView) findViewById(R.id.scheduledSwapStatus_text);

        // 📝 设置状态文本
        // 💡 作用：将丢帧统计信息显示在状态TextView上
        // ⏰ 使用时机：格式化字符串和获取控件后设置
        tv.setText(str);

        // 📝 str - 重新赋值为刷新率字符串
        // 💡 为什么复用：节省变量，逻辑上是连续的操作
        // 💡 作用：存储刷新率格式化后的文本
        // ⏰ 使用时机：状态文本设置后，用于刷新率显示
        str = getString(R.string.scheduledSwapRefresh, mRefreshPeriodNs);

        // 📝 tv - 重新赋值为刷新率TextView控件的引用
        // 💡 为什么复用：节省变量，逻辑上是连续的操作
        // 💡 作用：存储刷新率TextView引用，用于显示刷新率信息
        // ⏰ 使用时机：状态文本设置后获取，用于显示刷新率
        tv = (TextView) findViewById(R.id.scheduledSwapRefresh_text);

        // 📝 设置刷新率文本
        // 💡 作用：将刷新率信息显示在刷新率TextView上
        // ⏰ 使用时机：格式化字符串和获取控件后设置
        tv.setText(str);
    }

    /**
     * 🧵 渲染线程类
     * 💡 处理所有OpenGL渲染和帧调度
     */
    private static class RenderThread extends Thread {
        // 📬 Handler对象，必须在渲染线程创建，但UI线程也会使用
        private volatile RenderHandler mHandler;
        private ScheduledSwapActivity mActivity;  // 📱 Activity引用
        private Object mStartLock = new Object();  // 🔒 线程启动锁
        private boolean mReady = false;            // ✅ 线程是否就绪

        private volatile SurfaceHolder mSurfaceHolder;  // 🖼️ Surface持有者
        private EglCore mEglCore;                        // 🎮 EGL核心
        private WindowSurface mWindowSurface;            // 🖼️ 窗口Surface

        private int mUpdatePatternOffset;  // 📊 更新模式偏移
        private int mHoldFrames;           // ⏱️ 保持帧数
        private int mChoreographerSkips;   // ⚠️ Choreographer跳帧次数
        private int mDroppedFrames;        // ⚠️ 丢帧次数
        private long mPreviousRefreshNs;   // ⏱️ 上次刷新时间

        private int mUpdatePatternIdx;     // 📊 更新模式索引
        private int mFramesAheadIdx;       // ⏱️ 提前帧数索引
        private int mWidth, mHeight;       // 📐 Surface尺寸
        private int mPosition;             // 📍 移动方块位置
        private int mSpeed;                // 🚀 移动速度
        private int mBlockWidth;           // 📐 方块宽度
        private long mRefreshPeriodNs = -1; // ⏱️ 刷新周期

        /**
         * 🔧 构造函数
         * @param holder Surface持有者
         * @param activity Activity引用
         */
        public RenderThread(SurfaceHolder holder, ScheduledSwapActivity activity) {
            mSurfaceHolder = holder;
            mActivity = activity;
            mRefreshPeriodNs = MiscUtils.getDisplayRefreshNsec(activity);
        }

        /**
         * 🧵 线程入口点
         * 创建Looper、EGL上下文，然后进入消息循环
         */
        @Override
        public void run() {
            // 📝 准备Looper
            // 作用：为当前线程创建消息队列，使Handler可以接收消息
            // 使用时机：线程入口最先调用，必须在Handler创建前
            Looper.prepare();

            // 📝 创建渲染Handler
            // 作用：绑定当前线程的Looper，接收UI线程发来的消息
            // 使用时机：Looper准备完成后创建
            mHandler = new RenderHandler(this);

            // 📝 创建EGL核心对象
            // 作用：初始化OpenGL ES上下文，支持录制和GLES3
            // 使用时机：Handler创建后初始化GL环境
            // FLAG_RECORDABLE: 支持录制
            // FLAG_TRY_GLES3: 尝试使用OpenGL ES 3.0
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);

            // 📝 同步块：通知UI线程渲染线程已就绪
            // 作用：通过锁机制唤醒 waitUntilReady() 中等待的UI线程
            // 使用时机：初始化完成后立即通知
            synchronized (mStartLock) {
                // 📝 设置就绪标志
                // 作用：标记渲染线程已完成初始化
                // 使用时机：所有初始化操作完成后设置
                mReady = true;

                // 📝 唤醒等待线程
                // 作用：通知 waitUntilReady() 中的UI线程继续执行
                // 使用时机：就绪标志设置后立即唤醒
                mStartLock.notify();    // signal waitUntilReady()
            }

            // 📝 进入消息循环
            // 作用：开始处理消息队列中的消息，阻塞直到 Looper.quit()
            // 使用时机：初始化完成后进入循环，持续处理渲染消息
            Looper.loop();

            // 📝 记录退出日志
            // 作用：确认消息循环已退出
            // 使用时机：Looper.quit() 后打印
            Log.d(TAG, "looper quit");

            // 📝 释放GL资源
            // 作用：清理 WindowSurface 等OpenGL资源
            // 使用时机：消息循环退出后释放
            releaseGl();

            // 📝 释放EGL核心
            // 作用：销毁EGL上下文和显示连接
            // 使用时机：GL资源释放后释放EGL
            mEglCore.release();

            // 📝 同步块：标记渲染线程已停止
            // 作用：更新就绪标志，表示线程已完全退出
            // 使用时机：所有资源释放后更新
            synchronized (mStartLock) {
                // 📝 清除就绪标志
                // 作用：标记渲染线程已停止运行
                // 使用时机：线程即将退出时设置
                mReady = false;
            }
        }

        /**
         * ⏳ 等待渲染线程就绪
         */
        public void waitUntilReady() {
            // 📝 同步块：等待渲染线程就绪
            // 作用：通过锁机制阻塞UI线程，直到渲染线程完成初始化
            // 使用时机：UI线程调用，确保渲染Handler可用
            synchronized (mStartLock) {
                // 📝 循环检查就绪标志
                // 作用：防止虚假唤醒，确保渲染线程真正就绪
                // 使用时机：每次被唤醒后重新检查
                while (!mReady) {
                    // 📝 等待通知
                    // 作用：阻塞当前线程，释放锁，等待 renderThread.notify()
                    // 使用时机：就绪标志为false时等待
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /** 🔚 关闭所有资源 */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        /** 📤 获取渲染线程的Handler */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /** 🖼️ Surface创建处理 */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /**
         * 🔧 准备GL环境
         * 创建窗口Surface并设为当前
         */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");
            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();
        }

        /**
         * 🗑️ 释放GL资源
         */
        private void releaseGl() {
            // 📝 检查GL错误（释放前）
            // 作用：捕获释放前的GL状态问题，便于调试
            // 使用时机：释放操作开始时检查
            GlUtil.checkGlError("releaseGl start");

            // 📝 检查WindowSurface是否已创建
            // 作用：防御性检查，避免对空对象调用release
            // 使用时机：释放前判断
            if (mWindowSurface != null) {
                // 📝 释放窗口Surface
                // 作用：销毁EGLSurface，解除与Native Surface的绑定
                // 使用时机：确认Surface存在后释放
                mWindowSurface.release();

                // 📝 清空引用
                // 作用：释放引用，允许GC回收对象
                // 使用时机：释放后清空
                mWindowSurface = null;
            }

            // 📝 检查GL错误（释放后）
            // 作用：确认释放操作没有引入新的GL错误
            // 使用时机：释放操作完成后检查
            GlUtil.checkGlError("releaseGl done");

            // 📝 解绑当前上下文
            // 作用：确保没有线程持有GL上下文，安全释放资源
            // 使用时机：所有GL资源释放后解绑
            mEglCore.makeNothingCurrent();
        }

        /**
         * 📐 Surface尺寸变化处理
         */
        private void surfaceChanged(int width, int height) {
            // 📝 记录尺寸变化日志
            // 作用：调试Surface尺寸变化
            // 使用时机：方法入口立即打印
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // 📝 保存Surface宽度
            // 作用：用于计算方块位置和边界检测
            // 使用时机：绘制和位置计算时使用
            mWidth = width;

            // 📝 保存Surface高度
            // 作用：用于计算方块垂直位置
            // 使用时机：绘制时计算方块Y坐标
            mHeight = height;

            // 📝 计算方块宽度（Surface宽度的1/16）
            // 作用：让方块大小与屏幕宽度成比例
            // 使用时机：尺寸变化时重新计算，绘制时使用
            mBlockWidth = mWidth / 16;

            // 📝 重置方块位置到起始点
            // 作用：尺寸变化后从左边界重新开始移动
            // 使用时机：每次尺寸变化时重置
            mPosition = 0;

            // 📝 计算移动速度（每次移动的像素数）
            // 作用：让方块移动速度与屏幕宽度成比例
            // 使用时机：尺寸变化时重新计算，确保动画效果一致
            // +1 防止速度为0
            mSpeed = (mWidth / 120) + 1;
        }

        /**
         * ⚙️ 设置帧调度参数
         */
        private void setParameters(int updatePatternIndex, int framesAheadIndex) {
            // 📝 检查参数是否发生变化
            // 作用：避免重复设置相同的值，减少不必要的重置
            // 使用时机：参数传入时立即判断
            if (mUpdatePatternIdx != updatePatternIndex ||
                    mFramesAheadIdx != framesAheadIndex) {
                // 📝 更新帧率模式索引
                // 作用：选择 UPDATE_PATTERNS 中的某个模式（如"32"=24fps）
                // 使用时机：参数确实变化时更新
                mUpdatePatternIdx = updatePatternIndex;

                // 📝 更新提前帧数索引
                // 作用：选择 FRAME_AHEAD 中的某个值，决定提前调度几帧
                // 使用时机：参数确实变化时更新
                mFramesAheadIdx = framesAheadIndex;

                // 📝 重置模式偏移和保持帧数
                // 作用：新模式从头开始，清除之前的偏移状态
                // 使用时机：参数变化时重置，确保新模式正确开始
                mUpdatePatternOffset = mHoldFrames = 0;

                // 📝 记录参数更新日志
                // 作用：调试参数变化，确认新值生效
                // 使用时机：参数更新后打印
                Log.d(TAG, "Parameters now " + mUpdatePatternIdx + " / " + mFramesAheadIdx);
            }
        }

        /**
         * 🎬 处理Choreographer vsync事件
         * 💡 根据更新模式决定是否绘制，并设置呈现时间戳
         */
        public void doFrame(long frameTimeNs) {
            // 📝 调用advance推进帧状态
            // 作用：根据更新模式决定是否需要绘制，更新方块位置
            // 使用时机：vsync回调触发时立即调用
            // 返回值：true表示需要绘制新帧，false表示复用上一帧
            boolean draw = advance(frameTimeNs);

            // 📝 判断是否需要绘制
            // 作用：advance返回true才执行GL绘制操作
            // 使用时机：advance调用后根据返回值分支
            if (draw) {
                // 📝 开始systrace标记（绘制分支）
                // 作用：在systrace工具中标记此帧进行了绘制
                // 使用时机：绘制开始时标记，便于性能分析
                Trace.beginSection("doFrame draw");

                // 📝 设置当前GL上下文
                // 作用：绑定窗口Surface到当前线程的GL上下文
                // 使用时机：绘制前必须设置，确保GL命令作用于正确Surface
                mWindowSurface.makeCurrent();

                // 📝 执行绘制
                // 作用：调用GL命令绘制移动方块
                // 使用时机：GL上下文绑定后调用
                draw();

                // ⏰ 设置呈现时间戳
                // 📝 获取提前帧数
                // 作用：从 FRAME_AHEAD 数组中读取提前调度的帧数
                // 使用时机：绘制完成后，swapBuffers前获取
                int framesAhead = FRAME_AHEAD[mFramesAheadIdx];

                // 📝 判断是否需要设置呈现时间戳
                // 作用：framesAhead=0表示立即呈现，>0表示延迟呈现
                // 使用时机：获取提前帧数后判断
                if (framesAhead > 0) {
                    // 📝 计算呈现时间戳
                    // 作用：当前vsync时间 + 刷新周期 × 提前帧数 = 实际呈现时间
                    // 使用时机：需要延迟呈现时计算
                    long presentNs = frameTimeNs + mRefreshPeriodNs * framesAhead;

                    // 📝 设置呈现时间戳
                    // 作用：告诉SurfaceFlinger何时显示此帧，实现音视频同步
                    // 使用时机：计算完成后设置，swapBuffers前
                    mWindowSurface.setPresentationTime(presentNs);
                }

                // 📝 交换缓冲区
                // 作用：将后台缓冲区提交到前台，显示绘制内容
                // 使用时机：绘制和时间戳设置完成后交换
                mWindowSurface.swapBuffers();
            } else {
                // 📝 开始systrace标记（跳帧分支）
                // 作用：在systrace工具中标记此帧跳过绘制
                // 使用时机：不绘制时标记，便于性能分析
                Trace.beginSection("doFrame nodraw");
            }

            // 📝 结束systrace标记
            // 作用：关闭当前systrace区段
            // 使用时机：分支处理完成后统一结束
            Trace.endSection();
        }

        /** 📊 获取当前更新模式的保持时间 */
        private int getHoldTime() {
            char ch = UPDATE_PATTERNS[mUpdatePatternIdx].charAt(mUpdatePatternOffset);
            return ch - '0';
        }

        /**
         * 🔄 推进帧调度状态
         * 💡 核心逻辑：根据更新模式决定是否需要绘制、更新方块位置、检测丢帧
         * @param frameTimeNs Choreographer 提供的 vsync 时间戳（纳秒）
         * @return 是否需要重绘
         */
        private boolean advance(long frameTimeNs) {
            // 📝 标记本帧是否需要绘制
            // 作用：告诉调用方 doFrame() 是否执行 GL 绘制
            // 使用时机：方法末尾返回，决定 doFrame 是否调用 draw()
            boolean draw = false;

            // 📝 判断当前帧是否还在保持期间
            // 作用：如果 mHoldFrames > 1 说明此帧应跳过（不绘制），计数器减 1
            // 使用时机：每次 vsync 回调时判断是否复用上一帧
            if (mHoldFrames > 1) {
                mHoldFrames--;
            } else {
                // 📝 保持期结束，推进到更新模式的下一个偏移位置
                // 作用：循环遍历 UPDATE_PATTERNS 字符串，获取下一帧的保持时间
                // 使用时机：当前帧保持结束，准备绘制新帧
                mUpdatePatternOffset =
                        (mUpdatePatternOffset + 1) % UPDATE_PATTERNS[mUpdatePatternIdx].length();

                // 📝 获取新偏移位置对应的保持帧数
                // 作用：从模式字符串中读取字符，转换为需要保持的帧数
                // 使用时机：模式偏移更新后立即获取
                mHoldFrames = getHoldTime();

                // 📝 标记本帧需要绘制
                // 作用：表示这是一帧新内容，不是复用
                // 使用时机：模式推进成功后设为 true
                draw = true;

                // 📝 根据速度更新方块位置
                // 作用：让方块在水平方向移动 mSpeed 个像素
                // 使用时机：每次绘制新帧时更新位置
                mPosition += mSpeed;

                // 📝 判断方块是否到达边界
                // 作用：如果方块超出左右边界，反转移动方向实现反弹效果
                // 使用时机：位置更新后检查边界
                if (mPosition < -mSpeed || mPosition + mBlockWidth + mSpeed >= mWidth) {
                    mSpeed = -mSpeed;  // 🔄 反转方向
                }
            }

            // 📝 标记是否有性能问题需要抱怨（更新 UI）
            // 作用：当检测到跳帧或丢帧时设为 true，触发 UI 更新
            // 使用时机：下方检测逻辑中设置，最后用于决定是否更新控制面板
            boolean complain = false;

            // ⚠️ 检测Choreographer跳帧
            // 📝 判断 Choreographer 是否跳过了 vsync
            // 作用：两次 vsync 间隔超过一个刷新周期 + 1ms 容差，说明 Choreographer 跳帧了
            // 使用时机：每次 vsync 回调时检测，需要排除首次（mPreviousRefreshNs=0）
            if (mPreviousRefreshNs != 0 &&
                    frameTimeNs - mPreviousRefreshNs > mRefreshPeriodNs + ONE_MILLISECOND_NS) {
                // 📝 递增 Choreographer 跳帧计数
                // 作用：累计跳帧次数，用于 UI 显示
                // 使用时机：检测到跳帧时立即累加
                mChoreographerSkips++;
                complain = true;
                Log.d(TAG, frameTimeNs + ": Choreographer skip: " +
                        ((frameTimeNs - mPreviousRefreshNs) / 1000000.0) + " ms");
            }
            // 📝 更新上次 vsync 时间戳
            // 作用：为下一帧的跳帧检测提供基准
            // 使用时机：每次 vsync 回调结束时更新
            mPreviousRefreshNs = frameTimeNs;

            // ⚠️ 检测是否落后
            // 📝 计算当前系统时间与 vsync 时间的差值
            // 作用：如果差值超过一个刷新周期，说明渲染严重滞后（overrun）
            // 使用时机：每次 vsync 回调时检测渲染耗时
            long diff = System.nanoTime() - frameTimeNs;

            // 📝 判断是否发生了 overrun
            // 作用：差值超过刷新周期 - 1ms 容差，判定为丢帧
            // 使用时机：diff 计算后立即判断
            if (diff > mRefreshPeriodNs - ONE_MILLISECOND_NS) {
                Log.d(TAG, frameTimeNs + ": overrun: " + (diff / 1000000.0) + " ms");
                // 📝 递增丢帧计数
                // 作用：累计渲染超时导致的丢帧次数
                // 使用时机：检测到 overrun 时累加
                mDroppedFrames++;
                complain = true;
            }

            // 📝 如果检测到性能问题，更新 UI 显示
            // 作用：将丢帧和跳帧总数显示在 Activity 的 TextView 中
            // 使用时机：complain 为 true 时（有跳帧或丢帧）通过 UI 线程更新
            if (complain) {
                // 📝 切换到 UI 线程更新控件
                // 作用：确保 UI 更新在主线程执行（Android 要求）
                // 使用时机：检测到问题后立即调度
                mActivity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 📝 调用 Activity 的更新方法
                        // 作用：刷新 TextView 显示丢帧统计
                        // 使用时机：UI 线程执行此 Runnable 时
                        mActivity.updateControls(mDroppedFrames + mChoreographerSkips);
                    }
                 });
            }

            // 📝 返回是否需要绘制的标记
            // 作用：doFrame() 根据此值决定是否执行 GL 绘制和 swapBuffers
            // 使用时机：方法结束，供 doFrame 调用方使用
            return draw;
        }

        /**
         * 🖼️ 绘制场景
         * 💡 绘制一个移动的彩色方块
         */
        private void draw() {
            // 📝 检查GL错误（绘制前）
            // 作用：捕获绘制前的GL状态问题，便于调试
            // 使用时机：绘制操作开始时检查
            GlUtil.checkGlError("draw start");

            // 📝 设置清屏颜色为黑色
            // 作用：定义背景色为纯黑(RGBA: 0,0,0,1)
            // 使用时机：绘制前设置，后续glClear使用
            GLES20.glClearColor(0f, 0f, 0f, 1f);

            // 📝 清除颜色缓冲区
            // 作用：用黑色填充整个屏幕，清除上一帧内容
            // 使用时机：每帧绘制前清屏
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 📝 启用裁剪测试
            // 作用：允许只更新屏幕的特定区域（方块区域）
            // 使用时机：绘制方块前启用
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

            // 📝 设置裁剪区域
            // 作用：定义方块的绘制区域（位置、尺寸）
            // 参数：x=方块X位置, y=屏幕高度的2/8处, width=方块宽度, height=屏幕高度的1/8
            // 使用时机：启用裁剪后设置
            GLES20.glScissor(mPosition, mHeight * 2 / 8, mBlockWidth, mHeight / 8);

            // 💡 颜色根据丢帧状态变化
            // 📝 设置方块颜色（根据性能状态变化）
            // 作用：如果有丢帧，绿色通道闪烁；如果有跳帧，蓝色通道闪烁
            // 原理：用位运算取最低位（0或1），乘以1.0得到0.0或1.0
            // 使用时机：裁剪区域设置后设置颜色
            GLES20.glClearColor(1f, 1f * (mDroppedFrames & 0x01),
                    1f * (mChoreographerSkips & 0x01), 1f);

            // 📝 清除裁剪区域（绘制方块）
            // 作用：用当前颜色填充裁剪区域，实现方块绘制
            // 使用时机：颜色设置后绘制
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // 📝 禁用裁剪测试
            // 作用：恢复全屏绘制模式
            // 使用时机：方块绘制完成后禁用
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

            // 📝 检查GL错误（绘制后）
            // 作用：确认绘制操作没有引入新的GL错误
            // 使用时机：绘制操作完成后检查
            GlUtil.checkGlError("draw done");
        }
    }


    /**
     * 📬 渲染线程的Handler
     * 💡 用于UI线程向渲染线程发送消息
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_SET_PARAMETERS = 3;
        private static final int MSG_SHUTDOWN = 5;

        private WeakReference<RenderThread> mWeakRenderThread;  // 🔗 弱引用

        /** 🔧 构造函数，在渲染线程调用 */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /** 📤 发送Surface创建消息（UI线程调用） */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /** 📤 发送Surface变化消息（UI线程调用） */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                int width, int height) {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /** 📤 发送帧处理消息（UI线程调用） */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /** 📤 发送参数设置消息（UI线程调用） */
        public void sendSetParameters(int updatePatternIndex, int framesAheadIndex) {
            sendMessage(obtainMessage(RenderHandler.MSG_SET_PARAMETERS,
                    updatePatternIndex, framesAheadIndex));
        }

        /** 📤 发送关闭消息（UI线程调用） */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        /**
         * 📬 消息处理方法
         * 💡 从 UI 线程接收消息，分发给 RenderThread 的对应方法执行
         * 💡 通过弱引用访问 RenderThread，避免内存泄漏
         */
        @Override  // 📬 在渲染线程上执行消息处理
        public void handleMessage(Message msg) {
            // 📝 提取消息类型标识
            // 作用：根据 what 值决定执行哪个分支逻辑
            // 使用时机：switch 语句的判断条件
            int what = msg.what;

            // 📝 从弱引用获取 RenderThread 实例
            // 作用：安全地获取渲染线程引用，GC 后可能为 null
            // 使用时机：每次消息处理时先获取，为 null 则跳过处理
            RenderThread renderThread = mWeakRenderThread.get();

            // 📝 检查渲染线程是否已被回收
            // 作用：弱引用可能返回 null（Activity 已销毁），需安全处理
            // 使用时机：获取引用后立即判断
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            // 📝 根据消息类型分发处理
            switch (what) {
                case MSG_SURFACE_CREATED:
                    // 📝 处理 Surface 创建消息
                    // 作用：通知渲染线程初始化 GL 环境（创建 WindowSurface 等）
                    // 使用时机：UI 线程通过 sendSurfaceCreated() 发送此消息
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    // 📝 处理 Surface 尺寸变化消息
                    // 作用：通知渲染线程更新视口和方块尺寸参数
                    // 使用时机：SurfaceView 大小改变时触发
                    // msg.arg1=width, msg.arg2=height
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    // 📝 处理帧绘制消息
                    // 作用：从 msg.arg1/arg2 重建 64 位时间戳，调用渲染线程绘制帧
                    // 使用时机：Choreographer 的 vsync 回调通过 sendDoFrame() 发送
                    // 📝 重建时间戳：arg1 存高32位，arg2 存低32位
                    // 作用：long 在 Handler 消息中需拆分为两个 int 传递
                    // 使用时机：收到 MSG_DO_FRAME 消息后立即重组
                    long timestamp = (((long) msg.arg1) << 32) |
                                     (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_SET_PARAMETERS:
                    // 📝 处理参数设置消息
                    // 作用：通知渲染线程更新帧率模式和提前帧数
                    // 使用时机：用户在 Spinner 选择新参数时触发
                    // msg.arg1=updatePatternIndex, msg.arg2=framesAheadIndex
                    renderThread.setParameters(msg.arg1, msg.arg2);
                    break;
                case MSG_SHUTDOWN:
                    // 📝 处理关闭消息
                    // 作用：通知渲染线程退出 Looper 循环，释放 GL 资源
                    // 使用时机：Surface 销毁时通过 sendShutdown() 发送
                    renderThread.shutdown();
                    break;
               default:
                    // 📝 未知消息类型，抛出异常
                    // 作用：开发阶段捕获未预期的消息，防止静默忽略 bug
                    // 使用时机：收到不在上述 case 中的消息
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
