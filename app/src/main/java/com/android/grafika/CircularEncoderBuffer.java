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
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * 🔄 在环形缓冲区中保存编码后的视频数据。
 * Holds encoded video data in a circular buffer.
 * <p>
 * This is actually a pair of circular buffers, one for the raw data and one for the meta-data
 * (flags and PTS).
 * 实际上是一对环形缓冲区：一个存原始数据，一个存元数据（标志位和 PTS）。
 * <p>
 * Not thread-safe.
 * ⚠️ 非线程安全。
 */
public class CircularEncoderBuffer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean EXTRA_DEBUG = true;
    private static final boolean VERBOSE = false;

    // Raw data (e.g. AVC NAL units) held here.
    //
    // The MediaMuxer writeSampleData() function takes a ByteBuffer.  If it's a "direct"
    // ByteBuffer it'll access the data directly, if it's a regular ByteBuffer it'll use
    // JNI functions to access the backing byte[] (which, in the current VM, is done without
    // copying the data).
    //
    // It's much more convenient to work with a byte[], so we just wrap it with a ByteBuffer
    // as needed.  This is a bit awkward when we hit the edge of the buffer, but for that
    // we can just do an allocation and data copy (we know it happens at most once per file
    // save operation).
    // 📦 原始数据（如 AVC NAL 单元）存储在这里。
    //    MediaMuxer 的 writeSampleData() 接受 ByteBuffer。
    //    使用 byte[] 更方便，用 ByteBuffer 包装即可。
    //    到达缓冲区边缘时需要分配和复制（每个文件保存操作最多一次）。
    private ByteBuffer mDataBufferWrapper;  // 📋 数据缓冲区的 ByteBuffer 包装
    private byte[] mDataBuffer;             // 📦 实际数据存储

    // Meta-data held here.  We're using a collection of arrays, rather than an array of
    // objects with multiple fields, to minimize allocations and heap footprint.
    // 📊 元数据存储在这里。使用数组集合而非对象数组，以最小化分配和堆占用。
    private int[] mPacketFlags;        // 🏳️ 包标志位数组
    private long[] mPacketPtsUsec;     // ⏱️ 包呈现时间戳数组（微秒）
    private int[] mPacketStart;        // 📍 包起始位置数组
    private int[] mPacketLength;       // 📏 包长度数组

    // Data is added at head and removed from tail.  Head points to an empty node, so if
    // head==tail the list is empty.
    // ➡️ 数据从头部添加，从尾部移除。头部指向空节点，head==tail 表示列表为空。
    private int mMetaHead;  // 🔝 元数据头部索引
    private int mMetaTail;  // 🔻 元数据尾部索引

    /**
     * Allocates the circular buffers we use for encoded data and meta-data.
     * 🔧 分配用于编码数据和元数据的环形缓冲区。
     */
    public CircularEncoderBuffer(int bitRate, int frameRate, int desiredSpanSec) {
        // 📦 dataBufferSize: 数据缓冲区的总字节数
        //    作用：计算环形数据缓冲区需要多大空间
        //    公式：比特率(bps) × 时长(秒) / 8 = 字节数
        //    使用时机：分配 mDataBuffer 字节数组
        //    例如：2Mbps × 5秒 / 8 = 1.25MB
        int dataBufferSize = bitRate * desiredSpanSec / 8;

        // 📦 mDataBuffer: 实际存储编码数据的字节数组
        //    作用：环形缓冲区的数据存储区，存放 H.264 NAL 单元
        //    使用时机：add() 中写入数据，getChunk() 中读取数据
        mDataBuffer = new byte[dataBufferSize];

        // 📋 mDataBufferWrapper: 字节缓冲区包装器
        //    作用：将 byte[] 包装为 ByteBuffer，供 MediaMuxer.writeSampleData() 使用
        //    使用时机：getChunk() 中返回给调用者，避免不必要的数据复制
        mDataBufferWrapper = ByteBuffer.wrap(mDataBuffer);

        // 📊 metaBufferCount: 元数据槽位数量
        //    作用：预留足够的元数据条目空间（实际编码数据比元数据大得多）
        //    公式：帧率 × 时长 × 2（双倍预留确保不因元数据不足丢包）
        //    使用时机：分配 4 个元数据数组的长度
        int metaBufferCount = frameRate * desiredSpanSec * 2;

        // 🏳️ mPacketFlags: 每个数据包的标志位数组
        //    作用：存储 MediaCodec.BufferInfo.flags（如 SYNC_FRAME / END_OF_STREAM）
        //    使用时机：add() 写入，getFirstIndex() 查找关键帧
        mPacketFlags = new int[metaBufferCount];

        // ⏱️ mPacketPtsUsec: 每个数据包的呈现时间戳数组（微秒）
        //    作用：记录每帧的显示时间，供 MediaMuxer 排序
        //    使用时机：add() 写入，computeTimeSpanUsec() 读取
        mPacketPtsUsec = new long[metaBufferCount];

        // 📍 mPacketStart: 每个数据包在数据缓冲区中的起始偏移数组
        //    作用：记录数据在 mDataBuffer 中的位置
        //    使用时机：add() 写入，getChunk() / canAdd() 读取
        mPacketStart = new int[metaBufferCount];

        // 📏 mPacketLength: 每个数据包的字节长度数组
        //    作用：记录每个数据包占多少字节
        //    使用时机：add() 写入，getChunk() / getHeadStart() 读取
        mPacketLength = new int[metaBufferCount];

        if (VERBOSE) {
            Log.d(TAG, "CBE: bitRate=" + bitRate + " frameRate=" + frameRate +
                    " desiredSpan=" + desiredSpanSec + ": dataBufferSize=" + dataBufferSize +
                " metaBufferCount=" + metaBufferCount);
        }
    }

    /**
     * Computes the amount of time spanned by the buffered data, based on the presentation
     * time stamps.
     * ⏱️ 根据呈现时间戳计算缓冲数据的时间跨度（微秒）。
     */
    public long computeTimeSpanUsec() {
        // 📊 metaLen：元数据数组总长度
        // 💡 为什么定义：用于索引取模运算，实现环形访问
        // 💡 作用：确保索引在数组范围内循环
        // 💡 使用时机：计算beforeHead时取模
        final int metaLen = mPacketStart.length;

        // 🔍 if (mMetaHead == mMetaTail)：检查缓冲区是否为空
        // 💡 为什么检查：空缓冲区没有数据，无法计算时间跨度
        // 💡 作用：缓冲区为空时返回0
        // 💡 使用时机：每次调用时首先检查
        if (mMetaHead == mMetaTail) {
            // empty list
            // 📭 列表为空，返回0
            return 0;
        }

        // head points to the next available node, so grab the previous one
        // head 指向下一个可用节点，取前一个
        // 📍 beforeHead：头部前一个节点的索引
        // 💡 为什么计算：mMetaHead指向空节点，需要取前一个才是最新数据
        // 💡 作用：找到最近添加的数据包的时间戳
        // 💡 使用时机：读取最新时间戳时使用
        int beforeHead = (mMetaHead + metaLen - 1) % metaLen;
        // ⏱️ 返回时间跨度：最新时间戳 - 最旧时间戳
        // 💡 为什么计算：差值就是缓冲区中视频的总时长
        // 💡 作用：返回缓冲区中视频的时间跨度（微秒）
        return mPacketPtsUsec[beforeHead] - mPacketPtsUsec[mMetaTail];
    }

    /**
     * Adds a new encoded data packet to the buffer.
     * ➕ 向缓冲区添加新的编码数据包。如果空间不足，会先移除尾部旧数据。
     *
     * @param buf The data.  Set position() to the start offset and limit() to position+size.
     *     The position and limit may be altered by this method. 数据缓冲区
     * @param size Number of bytes in the packet. 数据包字节数（未使用，从 buf 计算）
     * @param flags MediaCodec.BufferInfo flags. 编码器标志位
     * @param ptsUsec Presentation time stamp, in microseconds. 呈现时间戳（微秒）
     */
    public void add(ByteBuffer buf, int flags, long ptsUsec) {
        // 📏 size：当前数据包的字节数
        // 💡 为什么计算：需要知道数据包大小才能判断是否有足够空间
        // 💡 作用：从buf的position到limit计算实际数据量
        // 💡 使用时机：传给canAdd()检查空间，以及后续复制数据
        int size = buf.limit() - buf.position();
        // 📝 VERBOSE日志：记录添加的数据包信息
        // 💡 为什么记录：调试时追踪环形缓冲区的数据添加情况
        // 💡 作用：在logcat中显示数据包大小、标志位和时间戳
        // 💡 使用时机：每次add()调用时（仅VERBOSE模式）
        if (VERBOSE) {
            Log.d(TAG, "add size=" + size + " flags=0x" + Integer.toHexString(flags) +
                    " pts=" + ptsUsec);
        }
        // 🔄 while (!canAdd(size))：循环移除尾部数据，直到有足够空间
        // 💡 为什么循环：环形缓冲区空间不足时需要淘汰旧数据
        // 💡 作用：确保新数据包能被添加到缓冲区
        // 💡 使用时机：每次添加数据前检查空间，不足时移除尾部
        while (!canAdd(size)) {
            removeTail();  // 🗑️ 移除尾部旧数据
        }

        // 📦 dataLen：数据缓冲区总长度（字节）
        // 💡 为什么定义：用于判断数据是否跨越缓冲区边界
        // 💡 作用：作为环形缓冲区的容量上限
        // 💡 使用时机：与packetStart+size比较决定复制方式
        final int dataLen = mDataBuffer.length;
        // 📊 metaLen：元数据数组总长度
        // 💡 为什么定义：用于索引取模运算，实现环形访问
        // 💡 作用：确保索引在数组范围内循环
        // 💡 使用时机：推进mMetaHead时取模
        final int metaLen = mPacketStart.length;
        // 📍 packetStart：当前数据包在数据缓冲区中的起始偏移量
        // 💡 为什么定义：需要知道数据写入到缓冲区的哪个位置
        // 💡 作用：记录数据包的起始位置，供后续读取使用
        // 💡 使用时机：复制数据到mDataBuffer，以及更新mPacketStart数组
        int packetStart = getHeadStart();
        // 🏳️ mPacketFlags[mMetaHead]：保存当前数据包的标志位
        // 💡 为什么保存：getFirstIndex()需要检查SYNC_FRAME标志找关键帧
        // 💡 作用：标记数据包类型（关键帧/流结束等）
        // 💡 使用时机：getFirstIndex()查找同步帧，getChunk()填充BufferInfo
        mPacketFlags[mMetaHead] = flags;
        // ⏱️ mPacketPtsUsec[mMetaHead]：保存当前数据包的呈现时间戳
        // 💡 为什么保存：computeTimeSpanUsec()需要时间戳计算时长
        // 💡 作用：记录帧的显示时间，供MediaMuxer排序使用
        // 💡 使用时机：computeTimeSpanUsec()计算时间跨度，getChunk()填充BufferInfo
        mPacketPtsUsec[mMetaHead] = ptsUsec;
        // 📍 mPacketStart[mMetaHead]：保存当前数据包的起始偏移量
        // 💡 为什么保存：getChunk()需要知道从哪里读取数据
        // 💡 作用：记录数据在mDataBuffer中的位置
        // 💡 使用时机：getChunk()读取数据，canAdd()检查空间
        mPacketStart[mMetaHead] = packetStart;
        // 📏 mPacketLength[mMetaHead]：保存当前数据包的长度
        // 💡 为什么保存：getChunk()需要知道读取多少字节
        // 💡 作用：记录每个数据包的字节数
        // 💡 使用时机：getChunk()设置BufferInfo.size，getHeadStart()计算偏移
        mPacketLength[mMetaHead] = size;

        // 📦 将数据从buf复制到环形数据缓冲区
        // 💡 为什么复制：环形缓冲区需要持久化存储编码数据
        // 💡 作用：将ByteBuffer中的编码数据写入mDataBuffer字节数组
        // 💡 使用时机：元数据保存完成后立即复制数据
        if (packetStart + size < dataLen) {
            // 📦 数据连续：数据包不跨越缓冲区边界
            // 💡 为什么判断：连续数据可以直接复制，无需分段
            // 💡 作用：优化性能，避免不必要的分段处理
            buf.get(mDataBuffer, packetStart, size);
        } else {
            // 📦📦 数据跨越边界：需要分两段复制
            // 💡 为什么分段：环形缓冲区回绕时，数据会分成两部分
            // 💡 作用：正确处理跨越边界的复制操作
            // 📏 firstSize：第一段数据的字节数
            // 💡 为什么计算：需要知道从packetStart到缓冲区末尾有多少字节
            // 💡 作用：确定第一段复制的长度
            // 💡 使用时机：复制第一段数据，以及计算第二段长度
            int firstSize = dataLen - packetStart;
            // 📝 VERBOSE日志：记录分段复制信息
            if (VERBOSE) { Log.v(TAG, "split, firstsize=" + firstSize + " size=" + size); }
            buf.get(mDataBuffer, packetStart, firstSize);  // 📋 复制第一段
            buf.get(mDataBuffer, 0, size - firstSize);     // 📋 复制第二段（从头开始）
        }

        // ➡️ mMetaHead = (mMetaHead + 1) % metaLen：推进元数据头部索引
        // 💡 为什么推进：指向下一个空闲的元数据槽位
        // 💡 作用：实现环形缓冲区的循环写入
        // 💡 使用时机：每次添加数据后更新
        mMetaHead = (mMetaHead + 1) % metaLen;

        // 🔍 EXTRA_DEBUG：调试模式检查
        // 💡 为什么检查：在调试模式下用特殊值填充头部位置
        // 💡 作用：如果代码错误地读取了头部位置，这些异常值会暴露问题
        // 💡 使用时机：每次添加数据后（仅EXTRA_DEBUG模式）
        if (EXTRA_DEBUG) {
            mPacketFlags[mMetaHead] = 0x77aaccff;           // 🏳️ 异常标志值
            mPacketPtsUsec[mMetaHead] = -1000000000L;       // ⏱️ 异常时间戳值
            mPacketStart[mMetaHead] = -100000;              // 📍 异常偏移值
            mPacketLength[mMetaHead] = Integer.MAX_VALUE;   // 📏 异常长度值
        }
    }

    /**
     * Returns the index of the oldest sync frame.  Valid until the next add().
     * <p>
     * When sending output to a MediaMuxer, start here.
     * 🔍 查找最旧的同步帧（关键帧）索引，供 MediaMuxer 使用。
     *    有效期直到下次 add() 调用。
     */
    // 🔍 getFirstIndex：查找环形缓冲区中最旧的同步帧（关键帧）索引
    // 💡 为什么定义：MP4文件必须从关键帧开始写入，否则无法正确解码
    // 💡 作用：为saveVideo()提供遍历环形缓冲区的起始位置
    // 💡 使用时机：保存视频时首先调用，获取起始帧索引
    public int getFirstIndex() {
        // 📊 metaLen：元数据数组总长度
        // 💡 为什么定义：用于索引取模运算，实现环形访问
        // 💡 作用：确保索引在数组范围内循环
        // 💡 使用时机：推进index时取模
        final int metaLen = mPacketStart.length;

        // 📍 index：当前遍历位置，从尾部开始扫描
        // 💡 为什么从尾部开始：尾部是最旧的数据，找到的第一个同步帧就是最旧的关键帧
        // 💡 作用：从mMetaTail遍历到mMetaHead，查找同步帧
        // 💡 使用时机：作为while循环的起始索引
        int index = mMetaTail;
        // 🔄 while (index != mMetaHead)：遍历所有有效数据包
        // 💡 为什么循环：需要逐个检查每个数据包是否为同步帧
        // 💡 作用：从尾部扫描到头部，查找BUFFER_FLAG_SYNC_FRAME标志
        // 💡 使用时机：index不等于头部时持续检查
        while (index != mMetaHead) {
            // 🔍 检查当前数据包是否为同步帧（关键帧）
            // 💡 为什么检查：只有同步帧可以作为MP4文件的起始帧
            // 💡 作用：通过位与操作检查BUFFER_FLAG_SYNC_FRAME标志位
            // 💡 使用时机：每次循环迭代时检查
            if ((mPacketFlags[index] & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0) {
                break;  // 🎯 找到同步帧，退出循环
            }
            // ➡️ index = (index + 1) % metaLen：推进到下一个数据包
            // 💡 为什么推进：继续向后扫描查找同步帧
            // 💡 作用：取模实现环形索引回绕
            // 💡 使用时机：当前帧不是同步帧时推进
            index = (index + 1) % metaLen;
        }

        // 🔍 检查是否遍历完整个缓冲区仍未找到同步帧
        // 💡 为什么检查：如果没有同步帧，无法生成有效的MP4文件
        // 💡 作用：index == mMetaHead 说明已遍历完所有帧都没找到
        // 💡 使用时机：while循环结束后检查
        if (index == mMetaHead) {
            Log.w(TAG, "HEY: could not find sync frame in buffer");  // ⚠️ 未找到同步帧
            // 🔄 index = -1：设置为无效索引，表示查找失败
            // 💡 为什么设置-1：-1表示没有可用的起始帧，调用方需要处理此情况
            // 💡 作用：saveVideo()收到-1后会回调fileSaveComplete(1)报告失败
            // 💡 使用时机：未找到同步帧时设置
            index = -1;
        }
        // 📤 返回找到的同步帧索引（-1表示未找到）
        return index;
    }

    /**
     * Returns the index of the next packet, or -1 if we've reached the end.
     * ➡️ 获取下一个数据包索引，到达末尾返回 -1。
     */
    // ➡️ getNextIndex：获取环形缓冲区中下一个数据包的索引
    // 💡 为什么定义：saveVideo()需要遍历环形缓冲区，逐帧写入MP4
    // 💡 作用：从当前索引推进到下一个有效数据包
    // 💡 使用时机：每帧写入后调用，继续下一帧
    public int getNextIndex(int index) {
        // 📊 metaLen：元数据数组总长度
        // 💡 为什么定义：用于索引取模运算，实现环形访问
        // 💡 作用：确保索引在数组范围内循环
        // 💡 使用时机：推进index时取模
        final int metaLen = mPacketStart.length;
        // ➡️ next：下一个数据包的索引
        // 💡 为什么计算：需要推进到环形缓冲区的下一个位置
        // 💡 作用：(index + 1) % metaLen 实现环形推进
        // 💡 使用时机：赋值后与mMetaHead比较
        int next = (index + 1) % metaLen;
        // 🔍 检查是否已到达有效数据的末尾
        // 💡 为什么检查：next == mMetaHead 说明已遍历完所有有效数据
        // 💡 作用：到达末尾返回-1，让saveVideo()的do-while循环终止
        // 💡 使用时机：推进索引后立即检查
        if (next == mMetaHead) {
            next = -1;  // 🏁 到达末尾，返回-1表示遍历完成
        }
        // 📤 返回下一个索引（-1表示已遍历完所有数据）
        return next;
    }

    /**
     * Returns a reference to a "direct" ByteBuffer with the data, and fills in the
     * BufferInfo.
     * <p>
     * The caller must not modify the contents of the returned ByteBuffer.  Altering
     * the position and limit is allowed.
     * 📦 获取指定索引的数据块引用，并填充 BufferInfo。
     *    调用者不得修改返回 ByteBuffer 的内容，但可以修改 position 和 limit。
     */
    public ByteBuffer getChunk(int index, MediaCodec.BufferInfo info) {
        // 📦 dataLen: 数据缓冲区总长度（字节）
        //    作用：判断数据是否跨越环形缓冲区边界
        //    使用时机：与 packetStart + length 比较决定是否需要分段处理
        final int dataLen = mDataBuffer.length;

        // 📍 packetStart: 指定索引的数据包起始偏移量
        //    作用：从元数据数组中读取该数据包在数据缓冲区中的位置
        //    使用时机：判断数据连续性，以及复制数据时的起始位置
        int packetStart = mPacketStart[index];

        // 📏 length: 指定索引的数据包字节长度
        //    作用：从元数据数组中读取该数据包包含的字节数
        //    使用时机：设置 BufferInfo.size，以及复制数据时计算第二段大小
        int length = mPacketLength[index];

        // 🏳️ info.flags: 填充 BufferInfo 的标志位字段
        //    作用：标识该数据包是否为同步帧、流结束等
        //    使用时机：MediaMuxer.writeSampleData() 需要此信息
        info.flags = mPacketFlags[index];

        // 📍 info.offset: 填充 BufferInfo 的偏移量字段
        //    作用：告诉 MediaMuxer 数据从缓冲区的哪个位置开始
        //    使用时机：连续数据时为 packetStart，跨越边界时重置为 0
        info.offset = packetStart;

        // ⏱️ info.presentationTimeUs: 填充 BufferInfo 的时间戳字段
        //    作用：记录帧的呈现时间，供 MediaMuxer 排序和同步
        //    使用时机：从元数据数组读取微秒级时间戳
        info.presentationTimeUs = mPacketPtsUsec[index];

        // 📏 info.size: 填充 BufferInfo 的数据大小字段
        //    作用：告诉 MediaMuxer 该数据包包含多少字节
        //    使用时机：与 length 相同，表示数据包大小
        info.size = length;

        // 🔍 判断数据是否连续（不跨越缓冲区边界）
        if (packetStart + length <= dataLen) {
            // one chunk; return full buffer to avoid copying data
            // 📦 数据连续，返回完整缓冲区避免复制
            //    作用：优化性能，避免不必要的内存复制
            //    优点：直接返回包装器，零拷贝操作
            return mDataBufferWrapper;
        } else {
            // two chunks
            // 📦📦 数据跨越边界，需要分配新的 ByteBuffer 并拼接
            //    作用：处理环形缓冲区回绕时的数据分段问题

            // 📦 tempBuf: 临时直接字节缓冲区
            //    作用：存储跨越边界的数据包（两段拼接）
            //    使用时机：仅在数据跨越边界时分配，每个文件保存操作最多一次
            ByteBuffer tempBuf = ByteBuffer.allocateDirect(length);

            // 📏 firstSize: 第一段数据的字节数
            //    作用：计算从 packetStart 到缓冲区末尾的数据量
            //    使用时机：复制第一段数据的长度参数
            int firstSize = dataLen - packetStart;

            // 📋 复制第一段数据：从 packetStart 到缓冲区末尾
            tempBuf.put(mDataBuffer, mPacketStart[index], firstSize);

            // 📋 复制第二段数据：从缓冲区开头到剩余部分
            //    长度 = 总长度 - 第一段长度
            tempBuf.put(mDataBuffer, 0, length - firstSize);

            // 📍 重置偏移量为 0：因为 tempBuf 是新分配的，数据从头开始
            info.offset = 0;

            // 📤 返回拼接后的缓冲区
            return tempBuf;
        }
    }

    /**
     * Computes the data buffer offset for the next place to store data.
     * <p>
     * Equal to the start of the previous packet's data plus the previous packet's length.
     * 📍 计算下一个数据存储位置的偏移量 = 前一个包的起始 + 前一个包的长度。
     */
    private int getHeadStart() {
        // 🔍 检查缓冲区是否为空
        if (mMetaHead == mMetaTail) {
            // list is empty
            // 📭 列表为空，返回起始位置 0
            //    作用：空缓冲区时数据从头开始写入
            return 0;
        }

        // 📦 dataLen: 数据缓冲区总长度（字节）
        //    作用：用于取模运算，实现环形缓冲区的回绕
        //    使用时机：计算最终偏移量时取模
        final int dataLen = mDataBuffer.length;

        // 📊 metaLen: 元数据数组总长度
        //    作用：用于索引取模运算，实现环形访问
        //    使用时机：计算前一个节点的索引
        final int metaLen = mPacketStart.length;

        // 📍 beforeHead: 头部前一个节点的索引
        //    作用：找到最近添加的数据包（头部指向空节点，所以要取前一个）
        //    计算方式：(mMetaHead + metaLen - 1) % metaLen
        //    使用时机：读取该数据包的起始位置和长度
        int beforeHead = (mMetaHead + metaLen - 1) % metaLen;

        // 📤 返回下一个可用的起始偏移量
        //    公式：(前一个包的起始 + 前一个包的长度 + 1) % 数据缓冲区长度
        //    作用：确保新数据写在前一个包之后，考虑环形回绕
        return (mPacketStart[beforeHead] + mPacketLength[beforeHead] + 1) % dataLen;
    }

    /**
     * Determines whether this is enough space to fit "size" bytes in the data buffer, and
     * one more packet in the meta-data buffer.
     * 🔍 检查是否有足够空间添加指定大小的数据和一个元数据条目。
     *
     * @return True if there is enough space to add without removing anything. 是否可以添加而不需移除
     */
    private boolean canAdd(int size) {
        // 📦 dataLen: 数据缓冲区总长度（字节）
        //    作用：用于检查数据包大小是否超过缓冲区容量
        //    使用时机：与 size 比较判断数据包是否过大
        final int dataLen = mDataBuffer.length;

        // 📊 metaLen: 元数据数组总长度
        //    作用：用于索引取模运算和检查元数据空间
        //    使用时机：计算 nextHead 和检查元数据是否用尽
        final int metaLen = mPacketStart.length;

        // 🚨 检查数据包是否超过缓冲区容量
        //    作用：防止单个数据包超过整个缓冲区大小的异常情况
        if (size > dataLen) {
            throw new RuntimeException("Enormous packet: " + size + " vs. buffer " +
                    dataLen);
        }

        // 📭 检查缓冲区是否为空
        if (mMetaHead == mMetaTail) {
            // empty list
            // 📭 列表为空，肯定可以添加
            //    作用：空缓冲区时任何大小的数据都可以添加
            return true;
        }

        // Make sure we can advance head without stepping on the tail.
        // 确保推进头部不会踩到尾部

        // 📍 nextHead: 头部推进后的下一个索引
        //    作用：模拟推进头部，检查是否会与尾部重叠
        //    计算方式：(mMetaHead + 1) % metaLen
        //    使用时机：与 mMetaTail 比较判断元数据空间是否充足
        int nextHead = (mMetaHead + 1) % metaLen;

        // 🔍 检查元数据空间是否充足：推进头部后是否会与尾部重叠
        // 💡 为什么检查：元数据数组是环形的，头部推进后不能覆盖尾部数据
        // 💡 作用：nextHead == mMetaTail 说明元数据槽位已满
        // 💡 使用时机：确认数据空间前先检查元数据空间
        if (nextHead == mMetaTail) {
            // 📝 元数据空间不足时输出详细调试信息
            if (VERBOSE) {
                Log.v(TAG, "ran out of metadata (head=" + mMetaHead + " tail=" + mMetaTail +")");
            }
            return false;  // 🚫 元数据空间不足，无法添加新的元数据条目
        }

        // Need the byte offset of the start of the "tail" packet, and the byte offset where
        // "head" will store its data.
        // 检查数据缓冲区空间

        // 📍 headStart: 头部数据的起始偏移量
        //    作用：通过 getHeadStart() 计算下一个数据写入位置
        //    使用时机：与 tailStart 一起计算可用空间
        int headStart = getHeadStart();

        // 📍 tailStart: 尾部数据包的起始偏移量
        //    作用：从元数据数组中读取最旧数据包的位置
        //    使用时机：与 headStart 一起计算可用空间
        int tailStart = mPacketStart[mMetaTail];

        // 📏 freeSpace: 数据缓冲区中的可用空间（字节）
        //    作用：计算从头部写入位置到尾部数据之间的空闲空间
        //    计算方式：(tailStart + dataLen - headStart) % dataLen
        //    使用时机：与 size 比较判断数据空间是否充足
        int freeSpace = (tailStart + dataLen - headStart) % dataLen;

        // 🔍 检查数据空间是否充足：请求的字节数是否超过可用空间
        // 💡 为什么检查：数据缓冲区也是环形的，写入不能覆盖尾部数据
        // 💡 作用：size > freeSpace 说明需要先移除尾部旧数据才能腾出空间
        // 💡 使用时机：元数据空间检查通过后，再检查数据空间
        if (size > freeSpace) {
            // 📝 数据空间不足时输出详细调试信息（包含头部位置、尾部位置、请求大小、可用空间）
            if (VERBOSE) {
                Log.v(TAG, "ran out of data (tailStart=" + tailStart + " headStart=" + headStart +
                    " req=" + size + " free=" + freeSpace + ")");
            }
            return false;  // 🚫 数据空间不足，需要先移除尾部数据
        }

        // ✅ 空间检查通过，输出调试信息
        if (VERBOSE) {
            Log.v(TAG, "OK: size=" + size + " free=" + freeSpace + " metaFree=" +
                    ((mMetaTail + metaLen - mMetaHead) % metaLen - 1));
        }

        // ✅ 所有检查通过，可以添加新数据
        return true;
    }

    /**
     * Removes the tail packet.
     * 🗑️ 移除尾部数据包。
     */
    private void removeTail() {
        // 🚨 检查缓冲区是否为空
        //    作用：防止在空缓冲区时执行移除操作
        //    使用时机：方法入口，立即验证缓冲区状态
        if (mMetaHead == mMetaTail) {
            throw new RuntimeException("Can't removeTail() in empty buffer");  // 🚨 空缓冲区无法移除
        }

        // 📊 metaLen: 元数据数组总长度
        //    作用：用于索引取模运算，实现环形访问
        //    使用时机：推进尾部索引时取模
        final int metaLen = mPacketStart.length;

        // ➡️ 推进尾部索引（取模实现环形）
        //    作用：将最旧的数据包标记为已移除，释放其空间
        //    公式：(mMetaTail + 1) % metaLen
        //    使用时机：空间不足时调用，为新数据腾出空间
        mMetaTail = (mMetaTail + 1) % metaLen;
    }
}
