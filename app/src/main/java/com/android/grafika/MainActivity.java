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

// 📦 包声明：定义这个类所在的包路径
package com.android.grafika;

// 📚 导入需要的类库，就像去超市买东西，需要先知道要买什么
import android.os.Bundle;                    // 🎁 Bundle：用于在Activity之间传递数据，像一个数据包裹
import android.app.ListActivity;             // 📋 ListActivity：专门显示列表的Activity基类
import android.content.Intent;               // 🎯 Intent：意图对象，用于启动其他Activity
import android.util.Log;                     // 📝 Log：日志工具，用于调试时打印信息
import android.view.Menu;                    // 🍔 Menu：菜单接口
import android.view.MenuItem;                // 🍟 MenuItem：菜单项
import android.view.View;                    // 👁️ View：所有UI组件的基类
import android.widget.ListView;              // 📜 ListView：列表视图组件
import android.widget.SimpleAdapter;         // 🔌 SimpleAdapter：简单的列表适配器

import java.util.ArrayList;                  // 📦 ArrayList：动态数组
import java.util.Collections;                // 🧰 Collections：集合工具类
import java.util.Comparator;                 // ⚖️ Comparator：比较器接口
import java.util.HashMap;                    // 🗺️ HashMap：键值对集合
import java.util.List;                       // 📋 List：列表接口
import java.util.Map;                        // 🗺️ Map：映射接口
import com.google.grafika.R;                 // 🎨 R：资源文件生成的类，包含布局、字符串等资源ID

/**
 * Main activity -- entry point from Launcher.
 * 
 * 🎬 主Activity - 这是应用的入口点，从启动器启动
 * 
 * 📱 这个类继承自 ListActivity，意味着它主要显示一个列表
 * 🎯 用户点击列表中的某一项，就会跳转到对应的演示Activity
 * 🎨 这是一个图形和视频处理的演示应用集合
 */
public class MainActivity extends ListActivity {
    // 🏷️ TAG：日志标签，用于在 Logcat 中过滤 MainActivity 的日志
    public static final String TAG = "Grafika";

    // 🗝️ map keys：Map集合中使用的键名常量
    // 📝 TITLE：标题键，用于获取列表项的标题
    private static final String TITLE = "title";
    // 📝 DESCRIPTION：描述键，用于获取列表项的描述信息
    private static final String DESCRIPTION = "description";
    // 📝 CLASS_NAME：类名键，用于存储对应的Activity类的Intent
    private static final String CLASS_NAME = "class_name";

    /**
     * Each entry has three strings: the test title, the test description, and the name of
     * the activity class.
     * 
     * 🎮 每个测试条目包含三个字符串：
     *    1️⃣ 测试标题（显示在列表中）
     *    2️⃣ 测试描述（解释这个功能做什么）
     *    3️⃣ Activity类名（点击后要跳转到哪个页面）
     * 
     * 💡 这是一个二维数组，每个内部数组代表一个测试用例
     * 🎨 带 * 号的是推荐体验的，{bench} 是性能测试，{util} 是工具，{~ignore} 是可忽略的
     */
    private static final String[][] TESTS = {
        // 🎬 播放视频（使用TextureView）- 用TextureView方式播放mp4视频
        { "* Play video (TextureView)",
            "Plays .mp4 videos created by Grafika",  // 📹 播放Grafika创建的mp4视频
            "PlayMovieActivity" },
        // 📸 连续捕获 - 持续录制摄像头，需要时保存快照
        { "Continuous capture",
            "Records camera continuously, saves a snapshot when requested",
            "ContinuousCaptureActivity" },
        // 🎥 双解码 - 并排解码两个视频
        { "Double decode",
            "Decodes two videos side-by-side",
            "DoubleDecodeActivity" },
        // 📐 硬件缩放测试 - 测试SurfaceHolder的setFixedSize方法
        { "Hardware scaler exerciser",
            "Exercises SurfaceHolder#setFixedSize()",
            "HardwareScalerActivity" },
        // 📷 实时摄像头（使用TextureView）- 简单地将摄像头预览显示到视图
        { "Live camera (TextureView)",
            "Trivially feeds the camera preview to a view",
            "LiveCameraActivity" },
        // 🖥️ 多Surface测试 - 三个重叠的SurfaceView，其中一个安全模式
        { "Multi-surface test",
            "Three overlapping SurfaceViews, one secure",
            "MultiSurfaceActivity" },
        // 🎞️ 播放视频（使用SurfaceView）- 用SurfaceView方式播放mp4视频
        { "Play video (SurfaceView)",
            "Plays .mp4 videos created by Grafika",
            "PlayMovieSurfaceActivity" },
        // 🎨 录制GL应用 - 使用FBO、重渲染或FB blit录制OpenGL应用
        { "Record GL app",
            "Records GL app with FBO, re-render, or FB blit",
            "RecordFBOActivity" },
        // 📺 使用MediaProjectionManager录制屏幕
        { "Record screen using MediaProjectionManager",
                "Screen recording using MediaProjectionManager and Virtual Display",
                "ScreenRecordActivity" },
        // ⏰ 定时交换 - 测试SurfaceFlinger的PTS处理
        { "Scheduled swap",
            "Exercises SurfaceFlinger PTS handling",
            "ScheduledSwapActivity" },
        // 📹 显示并捕获摄像头 - 显示摄像头预览，需要时录制
        { "Show + capture camera",
            "Shows camera preview, records when requested",
            "CameraCaptureActivity" },
        // 🖼️ TextureView中的简单GL - 尽快用OpenGL渲染
        { "Simple GL in TextureView",
            "Renders with GL as quickly as possible",
            "TextureViewGLActivity" },
        // 🎨 TextureView中的简单Canvas - 尽快用Canvas渲染
        { "Simple Canvas in TextureView",
            "Renders with Canvas as quickly as possible",
            "TextureViewCanvasActivity" },
        // 📷 从摄像头创建纹理 - 调整大小和缩放摄像头预览
        { "Texture from Camera",
            "Resize and zoom the camera preview",
            "TextureFromCameraActivity" },
        // ⚡ glReadPixels性能测试 - 测试720p帧的glReadPixels性能
        { "{bench} glReadPixels speed test",
            "Tests glReadPixels() performance with 720p frames",
            "ReadPixelsActivity" },
        // ⚡ glTexImage2D性能测试 - 测试512x512图像的glTexImage2D性能
        { "{bench} glTexImage2D speed test",
            "Tests glTexImage2D() performance on 512x512 image",
            "TextureUploadActivity" },
        // 🌈 彩条工具 - 显示RGB彩条
        { "{util} Color bars",
            "Shows RGB color bars",
            "ColorBarActivity" },
        // ℹ️ OpenGL ES信息 - 显示图形驱动信息
        { "{util} OpenGL ES info",
            "Dumps info about graphics drivers",
            "GlesInfoActivity" },
        // 🧪 测试用例（可忽略）
        { "{~ignore} Chor test",
            "Exercises bug",
            "ChorTestActivity" },
        // 🧪 测试用例（可忽略）
        { "{~ignore} Codec open test",
            "Exercises bug",
            "CodecOpenActivity" },
        // 🧪 测试用例（可忽略）
        { "{~ignore} Software input surface",
            "Exercises bug",
            "SoftInputSurfaceActivity" },
    };

    /**
     * Compares two list items.
     * 
     * ⚖️ 比较器：用于对列表项按标题进行排序
     * 💡 按字母顺序排列，让用户更容易找到想要的功能
     */
    private static final Comparator<Map<String, Object>> TEST_LIST_COMPARATOR =
            new Comparator<Map<String, Object>>() {
        /**
         * 比较两个Map对象
         * @param map1 第一个要比较的Map
         * @param map2 第二个要比较的Map
         * @return 负数：map1 < map2，0：相等，正数：map1 > map2
         */
        @Override
        public int compare(Map<String, Object> map1, Map<String, Object> map2) {
            // 📝 从两个Map中取出标题字符串
            String title1 = (String) map1.get(TITLE);
            String title2 = (String) map2.get(TITLE);
            // 🔤 使用字符串的compareTo方法进行比较（按字母顺序）
            return title1.compareTo(title2);
        }
    };


    /**
     * 🔧 Activity创建时调用的生命周期方法
     * 
     * @param savedInstanceState 保存的状态数据，用于恢复之前的状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 调用父类的onCreate，这是必须的（Android生命周期要求）
        // 💡 作用：执行Activity基类的初始化逻辑
        // ⏰ 使用时机：Activity创建时首先调用
        super.onCreate(savedInstanceState);
        // 🎨 设置Activity的布局文件，告诉Android界面应该长什么样
        // 💡 作用：加载XML布局文件到当前Activity
        // ⏰ 使用时机：super.onCreate()之后立即设置
        setContentView(R.layout.activity_main);

        // 🔧 一次性单例初始化；需要Activity上下文来获取文件位置
        // One-time singleton initialization; requires activity context to get file location.
        // 📦 初始化ContentManager，这是管理应用内容的核心类
        // 💡 作用：初始化应用内容管理器，准备演示所需的资源文件
        // ⏰ 使用时机：布局设置后，创建列表前初始化
        ContentManager.initialize(this);

        // 📋 设置列表适配器，创建SimpleAdapter
        // 💡 SimpleAdapter将数据显示在列表中
        // 📝 参数说明：
        //    - this：当前Activity作为上下文（用于获取资源和布局服务）
        //    - createActivityList()：创建Activity列表数据（返回List<Map<String, Object>>）
        //    - android.R.layout.two_line_list_item：使用系统提供的两行列表项布局（标题+描述）
        //    - new String[]{TITLE, DESCRIPTION}：Map中的键名（用于从Map中提取数据）
        //    - new int[]{android.R.id.text1, android.R.id.text2}：布局中的控件ID（用于显示数据）
        // 🎯 这个适配器会将createActivityList()返回的数据绑定到ListView上
        // 💡 作用：创建并配置列表适配器，将测试项数据绑定到ListView
        // ⏰ 使用时机：ContentManager初始化后设置
        setListAdapter(new SimpleAdapter(this, createActivityList(),
                android.R.layout.two_line_list_item, new String[] { TITLE, DESCRIPTION },
                new int[] { android.R.id.text1, android.R.id.text2 } ));

        // 📦 获取ContentManager单例实例（用于管理应用内容）
        // 💡 作用：获取已初始化的ContentManager实例
        // ⏰ 使用时机：设置列表适配器后获取
        ContentManager cm = ContentManager.getInstance();
        // ❓ 检查内容是否已经创建（避免重复创建）
        // 💡 作用：判断演示所需的资源文件是否已存在
        // ⏰ 使用时机：获取ContentManager后立即检查
        if (!cm.isContentCreated(this)) {
            // 🏗️ 如果没有创建，则创建所有需要的内容文件（演示视频等资源）
            // 💡 作用：生成演示应用所需的视频文件和资源
            // ⏰ 使用时机：检测到内容不存在时创建
            ContentManager.getInstance().createAll(this);
        }
    }

    /**
     * Creates the list of activities from the string arrays.
     * 
     * 📋 从字符串数组创建Activity列表
     * 💡 将TESTS数组转换为可以在ListView中显示的列表数据
     * 
     * @return 包含所有Activity信息的列表
     */
    private List<Map<String, Object>> createActivityList() {
        // 📦 创建一个空的列表，用于存放所有测试项（为什么：需要动态构建列表）
        // 🎯 作用：存储每个测试活动的信息（标题、描述、跳转Intent）
        // ⏰ 使用时机：在onCreate()中调用，为列表适配器提供数据
        List<Map<String, Object>> testList = new ArrayList<Map<String, Object>>();

        // 🔄 遍历TESTS数组中的每一个测试项（为什么：需要将静态数据转换为动态列表）
        // 🎯 作用：逐个处理TESTS中的每个测试用例
        // ⏰ 使用时机：遍历所有测试项，为每个项创建Map并添加到列表
        for (String[] test : TESTS) {
            // 🗺️ 创建一个临时的Map，用于存放单个测试项的数据（为什么：需要键值对存储）
            // 🎯 作用：存储当前测试项的标题、描述和Intent
            // ⏰ 使用时机：每个循环迭代开始时创建，用于暂存当前测试项数据
            Map<String, Object> tmp = new HashMap<String, Object>();
            // 📝 将测试标题放入Map（key="title"，value=测试标题字符串）
            // 🎯 作用：保存测试项的显示名称
            // ⏰ 使用时机：在添加描述之前，确保数据结构完整
            tmp.put(TITLE, test[0]);
            // 📝 将测试描述放入Map（key="description"，value=测试描述字符串）
            // 🎯 作用：保存测试项的详细说明
            // ⏰ 使用时机：紧接标题之后，补充测试项信息
            tmp.put(DESCRIPTION, test[1]);
            // 🎯 创建一个Intent对象，用于跳转到对应的Activity（为什么：需要启动其他页面）
            // 🎯 作用：封装跳转目标的信息
            // ⏰ 使用时机：在设置类名之前创建，用于后续的类名绑定
            Intent intent = new Intent();
            // ⚠️ 在这里解析类名，这样如果类名错误会立即崩溃
            // 💡 这样做的好处是：错误会在启动时就暴露，而不是在用户点击时才出错
            // Do the class name resolution here, so we crash up front rather than when the
            // activity list item is selected if the class name is wrong.
            try {
                // 🔍 使用Class.forName动态加载类（为什么：需要通过字符串获取Class对象）
                // 🎯 作用：将类名字符串转换为可使用的Class对象
                // ⏰ 使用时机：在try块中，处理可能的类加载失败
                // 📦 包名 + 类名拼接成完整路径
                Class cls = Class.forName("com.android.grafika." + test[2]);
                // 🎯 设置Intent要跳转的目标Activity（为什么：需要告诉Intent跳转到哪里）
                // 🎯 作用：将加载的类与Intent关联
                // ⏰ 使用时机：类加载成功后立即设置
                intent.setClass(this, cls);
                // 💾 将Intent存入Map（key="class_name"，value=Intent对象）
                // 🎯 作用：保存跳转信息，供后续点击事件使用
                // ⏰ 使用时机：Intent配置完成后存储
                tmp.put(CLASS_NAME, intent);
            } catch (ClassNotFoundException cnfe) {
                // ❌ 如果找不到类，抛出运行时异常（为什么：类名配置错误需要立即发现）
                // 🎯 作用：强制开发者修正错误的类名配置
                // ⏰ 使用时机：类加载失败时，立即终止程序
                throw new RuntimeException("Unable to find " + test[2], cnfe);
            }
            // ➕ 将这个测试项添加到列表中（为什么：需要累积所有测试项）
            // 🎯 作用：将完整的测试项数据加入最终列表
            // ⏰ 使用时机：每个循环迭代结束时，添加当前测试项
            testList.add(tmp);
        }

        // 🔀 使用比较器对列表进行排序（为什么：让列表按字母顺序排列，方便查找）
        // 🎯 作用：按标题字母顺序排列测试项
        // ⏰ 使用时机：所有测试项都添加到列表后，返回前排序
        Collections.sort(testList, TEST_LIST_COMPARATOR);

        // 📤 返回排序后的列表（为什么：调用者需要使用这个列表）
        // 🎯 作用：提供完整的、已排序的测试项列表
        // ⏰ 使用时机：方法结束时，返回给onCreate()使用
        return testList;
    }

    /**
     * 🖱️ 当用户点击列表项时调用的方法
     * 
     * @param listView 列表视图组件
     * @param view 被点击的具体视图
     * @param position 点击的位置索引
     * @param id 被点击项的ID
     */
    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        // 📍 获取被点击位置的数据（为什么：需要知道用户点了哪个项）
        // 🎯 作用：从列表中提取被点击项的完整数据
        // ⏰ 使用时机：用户点击列表项时立即调用
        // 💡 通过position参数定位到具体的Map对象
        Map<String, Object> map = (Map<String, Object>)listView.getItemAtPosition(position);
        // 🎯 获取对应的Intent（跳转意图）（为什么：需要知道跳转目标）
        // 🎯 作用：从Map中取出预先存储的Intent对象
        // ⏰ 使用时机：获取Map数据后，启动Activity之前
        // 💡 这个Intent是在createActivityList()中创建并存储的
        Intent intent = (Intent) map.get(CLASS_NAME);
        // 🚀 启动目标Activity（为什么：实现页面跳转）
        // 🎯 作用：启动用户选择的演示Activity
        // ⏰ 使用时机：获取Intent后立即启动
        // 💡 这是整个点击事件的最终动作
        startActivity(intent);
    }

    /**
     * 🍔 创建选项菜单的方法
     * 
     * @param menu 菜单对象
     * @return true表示菜单已处理
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 🍔 加载菜单资源文件（为什么：需要定义应用的菜单选项）
        // 🎯 作用：从XML资源文件中加载菜单布局
        // ⏰ 使用时机：首次创建选项菜单时调用
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        // ✅ 返回true表示菜单创建成功（为什么：Android系统需要知道菜单是否被处理）
        // 🎯 作用：告诉系统菜单已准备好显示
        // ⏰ 使用时机：方法结束时返回，决定菜单是否显示
        return true;
    }

    /**
     * onClick handler for "about" menu item.
     * 
     * ℹ️ "关于"菜单项的点击事件处理方法
     * 💡 当用户点击"关于"菜单时，显示关于对话框
     */
    public void clickAbout(@SuppressWarnings("unused") MenuItem unused) {
        // 📦 显示关于对话框
        AboutBox.display(this);
    }

    /**
     * onClick handler for "regenerate content" menu item.
     * 
     * 🔄 "重新生成内容"菜单项的点击事件处理方法
     * 💡 当用户点击"重新生成内容"时，重新创建所有测试内容
     */
    public void clickRegenerateContent(@SuppressWarnings("unused") MenuItem unused) {
        // 🏗️ 重新创建所有内容
        ContentManager.getInstance().createAll(this);
    }
}
