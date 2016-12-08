package com.szysky.customize.siv.imgprocess;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.ImageView;

import com.szysky.customize.siv.SImageView;
import com.szysky.customize.siv.util.CloseUtil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author :  suzeyu
 * Time   :  2016-12-07  下午1:26
 * Blog   :  http://szysky.com
 * GitHub :  https://github.com/suzeyu1992
 * ClassDescription : 图片的加载类
 */

public class ImageLoader {

    private static volatile ImageLoader mInstance;

    private Context mContext;

    private ImageLoader (Context context){
        mContext = context.getApplicationContext();
        mImageCache = new DefaultImageCache(mContext, this);
    }

    public static ImageLoader getInstance(Context context){
        if (null == mInstance){
            synchronized (ImageLoader.class){
                if (null == mInstance){
                    mInstance = new ImageLoader(context);
                }
            }
        }
        return mInstance;
    }


    private static final String TAG = ImageLoader.class.getName();
    IImageCache mImageCache  ;

    /**
     * 设置字节流一次缓冲的数据流大小
     */
    private static final int IO_BUFFER_SIZE = 8 * 1024;


    /**
     * 获得系统的cpu核数
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 分别对线程池中的核心线程数, 最大线程数, 最大最大存活定义常量
     */
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;
    /**
     * 创建线程工厂, 提供给ThreadPoolExecutor使用
     */
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };
    /**
     * 创建线程池, 用在异步加载图片时
     */
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            sThreadFactory

    );

    // 暴露注入的缓存策略
    public void setImageCache(IImageCache imageCache){
        mImageCache = imageCache;
    }


    /**
     *  对外提供的下载图片方法.
     *
     * @param imaUrl 需要加载的图片
     * @param sImageView  图片设置的控件
     * @param reqWidth  需要大小, 可以为0
     * @param reqHeight 需要大小, 可以为0
     */
    public void setPicture(final String imaUrl, final ImageView sImageView, final int reqWidth, final int reqHeight){

        Bitmap bitmap = mImageCache.get(imaUrl, reqWidth, reqHeight,null, false);
        if (null != bitmap){
            sImageView.setImageBitmap(bitmap);
            return;
        }

        Log.e(TAG, imaUrl+" "+System.currentTimeMillis() );
        mImageCache.get(imaUrl, reqWidth, reqHeight, sImageView, true);


        sImageView.setTag(imaUrl);

    }


    /**
     * 从一个地址下载并图片并转换成bitmap,  并先对bitmap进行磁盘的写入. 然后再返回
     */
    private Bitmap downloadBitmapFromUrl(String uriStr,final int reqWidth, final int reqHeight ) {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(uriStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
            // bitmap的缓存
            mImageCache.put(uriStr , bitmap, 0, 0);

        } catch (IOException e) {
            Log.e(TAG, "网络下载错误");
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            CloseUtil.close(in);
        }

        return bitmap;
    }

    /**
     * 从网络下载图片的流直接存入磁盘, 保证图片的原大小. 并在内部进行内存缓存添加.
     *
     */
    private boolean downloadFirstDiskToCache(String uriStr) {

        boolean result = false;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;
        try {
            URL url = new URL(uriStr);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);


            result =  mImageCache.putRawStream(uriStr, in);


        } catch (IOException e) {
            Log.e(TAG, ">>>>>>网络图片流直接存入磁盘失败");
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            CloseUtil.close(in);
            return result;
        }

    }

    /**
     * 发送Handler的标识
     */
    public static final int MESSAGE_POST_RESULT = 0x99;
    public static final int MESSAGE_DISK_GET_ERR = 0x98;

    /**
     * 利用主线程个Loop来创建一个Handler用来给图片设置bitmap前景
     */
    public Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                // 网络下载图片成功
                case MESSAGE_POST_RESULT:
                    LoaderResult result = (LoaderResult) msg.obj;

                    ImageView imageView = result.imageView;
                    String url = (String) imageView.getTag();
                    if (url.equals(result.url)) {
                        imageView.setImageBitmap(result.bitmap);
                    } else {
                        Log.w(TAG, "要设置的控件的url发生改变, so 取消设置图片");
                    }
                    break;

                // 从磁盘加载失败
                case MESSAGE_DISK_GET_ERR:

                    final LoaderResult errResult = (LoaderResult) msg.obj;

                    // 2. 创建一个Runable调用同步加载的方法去获取Bitmap
                    Runnable loadBitmapTask = new Runnable() {
                        @Override
                        public void run() {
                            Bitmap bitmap = null;
                            // 根据默认缓存添加的分支, 网络下载的输入流直接存入磁盘, 先进行bitmap转换可能会影响到原图片的大小
                            boolean result = downloadFirstDiskToCache(errResult.url);
                            if (result){
                                if (mImageCache instanceof DefaultImageCache){
                                    try {
                                         bitmap = ((DefaultImageCache) mImageCache).loadBitmapFromDiskCache(errResult.url, errResult.reqWidth, errResult.reqHeight);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }else{
                                // 通用逻辑, 从网络下载之后, 先把bitmap存入硬盘然后返回bitmap
                                // 一般情况下不会走此逻辑, 为了保险起见, 和后续扩展其他实现类可以保证bitmap会被添加到IImageView的put()回调中
                                bitmap = downloadBitmapFromUrl(errResult.url, errResult.reqWidth, errResult.reqHeight);
                            }

                            if (bitmap != null) {
                                LoaderResult loaderResult = new LoaderResult(errResult.imageView, errResult.url,bitmap, errResult.reqWidth, errResult.reqHeight);
                                mMainHandler.obtainMessage(MESSAGE_POST_RESULT, loaderResult).sendToTarget();
                                return;
                            }

                            Log.e(TAG, "在磁盘获取缓存失败发送handler后, 无法从网络获取到bitmap对象!!! " );
                        }
                    };


                    // 添加任务到线程池
                    THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
                    break;

                default:
                    super.handleMessage(msg);

            }

        }
    };

    static class LoaderResult {
        public ImageView imageView;
        public String url;
        public Bitmap bitmap;
        public int reqWidth;
        public int reqHeight;

        public LoaderResult(ImageView imageview, String urlStr, Bitmap bitmap, int reqWidth, int reqHeight) {
            imageView = imageview;
            url = urlStr;
            this.bitmap = bitmap;
            this.reqWidth = reqWidth;
            this.reqHeight = reqHeight;

        }
    }
}
