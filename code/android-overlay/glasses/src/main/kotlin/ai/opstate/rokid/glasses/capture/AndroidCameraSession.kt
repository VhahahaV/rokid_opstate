package ai.opstate.rokid.glasses.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.CaptureRequest as CameraRequest
import android.hardware.camera2.CaptureResult as CameraResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * A fresh Camera2 YUV frame becomes one upright JPEG per request. RG firmware's hardware
 * JPEG path stalled after shutter callbacks in device tests; this uses real sensor pixels,
 * not a replay image. Unrequested preview frames are immediately closed, never persisted.
 */
class AndroidCameraSession(context: Context) : WarmCameraSession {
    private val context=context.applicationContext
    private val manager=context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mutex=Mutex()
    private var worker: HandlerThread?=null
    private var handler: Handler?=null
    @Volatile private var device: CameraDevice?=null
    private var session: CameraCaptureSession?=null
    private var reader: ImageReader?=null
    private var sensorOrientation=0
    private var realtimeTimestamps=false
    private var configuredSize: Size?=null
    private var configuredRequest: Size?=null
    private var previewFrames=0
    private var exposureReady: CompletableDeferred<Unit>?=null
    val isOpen: Boolean get()=device!=null
    private data class Pending(val afterNanos: Long,val quality: Int,val result: CompletableDeferred<CameraJpeg>)
    @Volatile private var pending: Pending?=null
    override suspend fun openLowResolutionPreview() = mutex.withLock { ensureOpen(1280,720);awaitExposure() }
    private suspend fun ensureOpen(width: Int,height: Int) {
        check(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {"CAMERA_PERMISSION_REQUIRED"}
        if(device!=null && session!=null && configuredRequest==Size(width,height)) return
        closeResources()
        val thread=HandlerThread("OPSTATE-Camera").apply {start()}
        worker=thread;handler=Handler(thread.looper)
        val ids=manager.cameraIdList
        val id=ids.firstOrNull {manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}
            ?: ids.firstOrNull() ?: error("NO_CAMERA")
        val characteristics=manager.getCameraCharacteristics(id)
        sensorOrientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        realtimeTimestamps=characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val outputs=requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).getOutputSizes(ImageFormat.YUV_420_888)
        val size=chooseSize(outputs,width,height)
        val images=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,3)
        reader=images
        images.setOnImageAvailableListener({ source ->
            val image=runCatching {source.acquireLatestImage()}.getOrNull() ?: return@setOnImageAvailableListener
            val request=pending
            try {
                previewFrames++
                if(previewFrames==1) android.util.Log.i("OPSTATE-Camera","FIRST_YUV_FRAME clock_delta_ms=${(SystemClock.elapsedRealtimeNanos()-image.timestamp)/1_000_000}")
                if(request==null) return@setOnImageAvailableListener
                if(realtimeTimestamps && image.timestamp<request.afterNanos) return@setOnImageAvailableListener
                pending=null
                val epoch=if(realtimeTimestamps) System.currentTimeMillis()-(SystemClock.elapsedRealtimeNanos()-image.timestamp)/1_000_000 else System.currentTimeMillis()
                val frame=encode(image,request.quality,epoch)
                request.result.complete(frame)
                android.util.Log.i("OPSTATE-Camera","CAPTURE_ENCODED ${frame.width}x${frame.height} bytes=${frame.jpeg.size}")
            } catch(error: Exception) {request?.result?.completeExceptionally(error); pending=null}
            finally {image.close()}
        },handler)
        try {
            val opened=withTimeout(5000) {
                suspendCancellableCoroutine<CameraDevice> { continuation ->
                    manager.openCamera(id,object : CameraDevice.StateCallback() {
                        override fun onOpened(value: CameraDevice) {if(continuation.isActive) continuation.resume(value) else value.close()}
                        override fun onDisconnected(value: CameraDevice) {
                            value.close();pending?.result?.completeExceptionally(IllegalStateException("CAMERA_DISCONNECTED"))
                            if(continuation.isActive) continuation.resumeWithException(IllegalStateException("CAMERA_DISCONNECTED"))
                            device=null;session=null
                        }
                        override fun onError(value: CameraDevice,error: Int) {
                            value.close();pending?.result?.completeExceptionally(IllegalStateException("CAMERA_ERROR_$error"))
                            if(continuation.isActive) continuation.resumeWithException(IllegalStateException("CAMERA_ERROR_$error"))
                            device=null;session=null
                        }
                    },handler)
                }
            }
            device=opened
            val configured=withTimeout(5000) {
                suspendCancellableCoroutine<CameraCaptureSession> { continuation ->
                    opened.createCaptureSession(listOf(images.surface),object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {if(continuation.isActive) continuation.resume(value) else value.close()}
                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            value.close();if(continuation.isActive) continuation.resumeWithException(IllegalStateException("CAMERA_SESSION_FAILED"))
                        }
                    },handler)
                }
            }
            session=configured
            val repeating=opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(images.surface);set(CameraRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO)
            }
            val ready=CompletableDeferred<Unit>()
            exposureReady=ready
            configured.setRepeatingRequest(repeating.build(),object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession,request: CameraRequest,result: TotalCaptureResult) {
                    if(ready.isCompleted) return
                    val ae=result.get(CameraResult.CONTROL_AE_STATE)
                    val awb=result.get(CameraResult.CONTROL_AWB_STATE)
                    val exposureSettled=ae in setOf(CameraMetadata.CONTROL_AE_STATE_CONVERGED,CameraMetadata.CONTROL_AE_STATE_LOCKED,CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)
                    val whiteBalanceSettled=awb==null || awb in setOf(CameraMetadata.CONTROL_AWB_STATE_CONVERGED,CameraMetadata.CONTROL_AWB_STATE_LOCKED)
                    if(exposureSettled && whiteBalanceSettled) ready.complete(Unit)
                }
            },handler)
            configuredSize=size
            configuredRequest=Size(width,height)
            android.util.Log.i("OPSTATE-Camera","YUV_READY id=$id size=$size orientation=$sensorOrientation")
        } catch(error: Exception) {closeResources();throw error}
    }
    override suspend fun captureJpeg(width: Int,height: Int,jpegQuality: Int): CameraJpeg = mutex.withLock {
        require(width>0 && height>0 && width.toLong()*height<=1920L*1080 && jpegQuality in 1..100)
        ensureOpen(width,height)
        awaitExposure()
        val result=CompletableDeferred<CameraJpeg>()
        handler!!.post {
            runCatching {reader?.acquireLatestImage()?.close()}
            pending=Pending(SystemClock.elapsedRealtimeNanos(),jpegQuality,result)
        }
        try {withTimeout(5000) {result.await()}}
        catch(error: Exception) {closeResources();throw error}
        finally {pending=null}
    }
    private suspend fun awaitExposure() {
        try {withTimeout(3000) {requireNotNull(exposureReady).await()}}
        catch(error: Exception) {closeResources();throw error}
    }
    private fun encode(image: Image,quality: Int,epoch: Long): CameraJpeg {
        val width=image.width;val height=image.height
        val nv21=ByteArray(width*height*3/2)
        val y=image.planes[0];val u=image.planes[1];val v=image.planes[2]
        val yBuffer=y.buffer;val uBuffer=u.buffer;val vBuffer=v.buffer
        // A row of luma is contiguous whenever pixelStride is 1, so copy it in one bulk get instead of
        // width individual reads: 720 calls per frame rather than 921,600.
        if(y.pixelStride==1) for(row in 0 until height) {yBuffer.position(row*y.rowStride);yBuffer.get(nv21,row*width,width)}
        else for(row in 0 until height) for(col in 0 until width) nv21[row*width+col]=yBuffer.get(row*y.rowStride+col*y.pixelStride)
        for(row in 0 until height/2) for(col in 0 until width/2) {
            val index=width*height+row*width+col*2
            nv21[index]=vBuffer.get(row*v.rowStride+col*v.pixelStride)
            nv21[index+1]=uBuffer.get(row*u.rowStride+col*u.pixelStride)
        }
        val display=(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val screen=when(display.rotation) {Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
        val rotation=(sensorOrientation-screen+360)%360
        val initial=ByteArrayOutputStream().use {out->
            check(YuvImage(nv21,ImageFormat.NV21,width,height,null).compressToJpeg(Rect(0,0,width,height),if(rotation==0) quality else 100,out))
            out.toByteArray()
        }
        if(rotation==0) return CameraJpeg(initial,width,height,0,epoch)
        val original=requireNotNull(BitmapFactory.decodeByteArray(initial,0,initial.size))
        val rotated=Bitmap.createBitmap(original,0,0,width,height,Matrix().apply {postRotate(rotation.toFloat())},true)
        try {
            val bytes=ByteArrayOutputStream().use {out->check(rotated.compress(Bitmap.CompressFormat.JPEG,quality,out));out.toByteArray()}
            return CameraJpeg(bytes,rotated.width,rotated.height,0,epoch)
        } finally {if(rotated!==original) rotated.recycle();original.recycle()}
    }
    override suspend fun close()=mutex.withLock {closeResources()}
    private fun closeResources() {
        exposureReady?.cancel();exposureReady=null
        pending?.result?.cancel();pending=null
        runCatching {session?.stopRepeating()};runCatching {session?.close()};session=null
        runCatching {device?.close()};device=null
        reader?.close();reader=null
        worker?.quitSafely();worker=null;handler=null;configuredSize=null;configuredRequest=null;previewFrames=0
    }
    private fun chooseSize(sizes: Array<Size>?,width: Int,height: Int): Size {
        require(!sizes.isNullOrEmpty()) {"CAMERA_YUV_UNSUPPORTED"}
        return sizes.firstOrNull {it.width==width && it.height==height}
            ?: sizes.minBy {abs(it.width.toDouble()/it.height-width.toDouble()/height)*10+abs(it.width.toLong()*it.height-width.toLong()*height).toDouble()/(width.toLong()*height)}
    }
}
