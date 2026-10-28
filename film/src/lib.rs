//! JNI functions for `dev.otmgram.core.media.FilmLook`: tungsten's film looks on Android
//! bitmaps (ARGB_8888, which is RGBA bytes in memory), locked with libjnigraphics.

use std::ffi::c_void;

use cinestill::{Developer, Mask, MaskBuilder, Params};

type JniEnv = *mut c_void;
type JObject = *mut c_void;

#[repr(C)]
struct BitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

const FORMAT_RGBA_8888: i32 = 1;

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(env: JniEnv, bitmap: JObject, info: *mut BitmapInfo) -> i32;
    fn AndroidBitmap_lockPixels(env: JniEnv, bitmap: JObject, pixels: *mut *mut c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: JniEnv, bitmap: JObject) -> i32;
}

/// Runs [f] on the bitmap's pixels as one tightly packed RGBA buffer, if it is one.
unsafe fn with_pixels<T>(env: JniEnv, bitmap: JObject, f: impl FnOnce(&mut [u8], usize, usize) -> T) -> Option<T> {
    let mut info = BitmapInfo { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    if AndroidBitmap_getInfo(env, bitmap, &mut info) != 0 || info.format != FORMAT_RGBA_8888 || info.stride != info.width * 4 {
        return None;
    }
    let mut ptr: *mut c_void = std::ptr::null_mut();
    if AndroidBitmap_lockPixels(env, bitmap, &mut ptr) != 0 || ptr.is_null() {
        return None;
    }
    let (w, h) = (info.width as usize, info.height as usize);
    let pixels = std::slice::from_raw_parts_mut(ptr as *mut u8, w * h * 4);
    let result = f(pixels, w, h);
    AndroidBitmap_unlockPixels(env, bitmap);
    Some(result)
}

/// The glow's source, pooled from the full-size photo: a handle for `nativeDevelop`,
/// 0 if the bitmap can't be read. Freed with `nativeFreeMask`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_otmgram_core_media_FilmLook_nativeMask(env: JniEnv, _class: JObject, bitmap: JObject) -> i64 {
    with_pixels(env, bitmap, |pixels, w, h| {
        let mut builder = MaskBuilder::new(w, h);
        builder.add_rows(pixels);
        Box::into_raw(Box::new(builder.finish())) as i64
    })
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_otmgram_core_media_FilmLook_nativeFreeMask(_env: JniEnv, _class: JObject, mask: i64) {
    if mask != 0 {
        drop(Box::from_raw(mask as *mut Mask));
    }
}

/// Develops the bitmap in place with stock [stock] (tungsten's order), its glow from [mask]
/// (from this photo at any size). False if the bitmap can't be read.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_otmgram_core_media_FilmLook_nativeDevelop(
    env: JniEnv,
    _class: JObject,
    mask: i64,
    bitmap: JObject,
    stock: i32,
) -> bool {
    let Some(&(_, params)) = Params::STOCKS.get(stock as usize) else { return false };
    let mask = &*(mask as *const Mask);
    with_pixels(env, bitmap, |pixels, w, h| {
        Developer::new(mask, w, h, &params).develop(pixels, 0, 0, w, h);
    })
    .is_some()
}
