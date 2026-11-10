# Archive reader notices

Modular Files includes `me.zhanghai.android.libarchive:library:1.1.6`. The
published AAR includes `libarchive-jni.so` for Android. Its tagged build
statically links libarchive, bzip2, XZ Utils liblzma, LZ4, Zstandard, Mbed TLS
mbedcrypto, and the Android NDK's `libz.a` into that native library. These are
the corresponding license notices. The wrapper's tag pins the six upstream
submodules to the revisions below. The wrapper uses Android NDK
`28.1.13356709`; its zlib library is supplied by the NDK rather than by a
pinned source submodule.

| Component | Notice in this directory | Source at the pinned revision |
| --- | --- | --- |
| libarchive-android wrapper, Apache-2.0 | `libarchive-android-APACHE-2.0.txt` | https://github.com/zhanghai/libarchive-android/blob/v1.1.6/LICENSE |
| libarchive, BSD-style notice and exceptions | `libarchive-COPYING.txt` | https://github.com/libarchive/libarchive/blob/9525f90ca4bd14c7b335e2f8c84a4607b0af6bdf/COPYING |
| libarchive compress source, UC Regents notices | `libarchive-compress-UC-Regents-NOTICES.txt` | https://github.com/libarchive/libarchive/blob/9525f90ca4bd14c7b335e2f8c84a4607b0af6bdf/libarchive/archive_read_support_filter_compress.c and https://github.com/libarchive/libarchive/blob/9525f90ca4bd14c7b335e2f8c84a4607b0af6bdf/libarchive/archive_write_add_filter_compress.c |
| libarchive BLAKE2 source, Apache-2.0 option | `libarchive-BLAKE2-NOTICE.txt` | https://github.com/libarchive/libarchive/blob/9525f90ca4bd14c7b335e2f8c84a4607b0af6bdf/libarchive/archive_blake2s_ref.c |
| bzip2/libbzip2 | `bzip2-LICENSE.txt` | https://gitlab.com/bzip2/bzip2/-/blob/6a8690fc8d26c815e798c588f796eabe9d684cf0/LICENSE |
| XZ Utils liblzma, 0BSD | `xz-COPYING.txt`, `xz-COPYING.0BSD.txt` | https://github.com/tukaani-project/xz/blob/a522a226545730551f7e7c2685fab27cf567746c/COPYING and https://github.com/tukaani-project/xz/blob/a522a226545730551f7e7c2685fab27cf567746c/COPYING.0BSD |
| LZ4 library, BSD-2-Clause | `lz4-lib-LICENSE.txt` | https://github.com/lz4/lz4/blob/ebb370ca83af193212df4dcbadcc5d87bc0de2f0/lib/LICENSE |
| Zstandard, BSD license option | `zstd-LICENSE.txt` | https://github.com/facebook/zstd/blob/f8745da6ff1ad1e7bab384bd1f9d742439278e99/LICENSE |
| Mbed TLS mbedcrypto, Apache-2.0 option | `mbedtls-LICENSE.txt` | https://github.com/Mbed-TLS/mbedtls/blob/2fc8413bfcb51354c8e679141b17b3f1a5942561/LICENSE |
| Android NDK static zlib, zlib license | `zlib-LICENSE.txt` | https://android.googlesource.com/platform/external/zlib/+/f29fc757b1f27c182f36790eb0ccc6cf8ec27e8e/LICENSE |

Build evidence: [v1.1.6 CMake configuration](https://github.com/zhanghai/libarchive-android/blob/v1.1.6/library/CMakeLists.txt),
[pinned submodules](https://github.com/zhanghai/libarchive-android/tree/v1.1.6/library/src/main/jni/external),
and [NDK version](https://github.com/zhanghai/libarchive-android/blob/v1.1.6/library/build.gradle).
The zlib notice is from a pinned Android source revision; the AAR build does
not pin the NDK's internal zlib source commit separately.
