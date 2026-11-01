// Bắt log native của chính tiến trình (LiteRT / dispatch Samsung / ENN) để hiện lên màn hình,
// vì ứng dụng thường không đọc được logcat trực tiếp.
#include <jni.h>
#include <android/log.h>
#include <deque>
#include <mutex>
#include <string>

namespace {
std::mutex g_mu;
std::deque<std::string> g_lines;
bool g_on = false;

void Logger(const __android_log_message* m) {
  if (m && m->message && m->priority >= ANDROID_LOG_DEBUG) {
    std::lock_guard<std::mutex> l(g_mu);
    std::string s;
    static const char kP[] = {'?', '?', 'V', 'D', 'I', 'W', 'E', 'F', 'S'};
    int p = m->priority;
    s += kP[(p >= 0 && p <= 8) ? p : 0];
    s += '/';
    s += m->tag ? m->tag : "";
    s += ": ";
    s += m->message;
    g_lines.push_back(s);
    while (g_lines.size() > 300) g_lines.pop_front();
  }
  __android_log_logd_logger(m);
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_fluidframes_enn_NativeLog_nStart(JNIEnv*, jclass) {
  if (g_on) return;
  g_on = true;
  __android_log_set_logger(Logger);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fluidframes_enn_NativeLog_nClear(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> l(g_mu);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fluidframes_enn_NativeLog_nDrain(JNIEnv* env, jclass, jint maxLines) {
  std::string out;
  {
    std::lock_guard<std::mutex> l(g_mu);
    size_t n = g_lines.size();
    size_t from = n > (size_t)maxLines ? n - maxLines : 0;
    for (size_t i = from; i < n; ++i) { out += g_lines[i]; out += '\n'; }
  }
  // bỏ byte không hợp lệ UTF-8 đơn giản
  for (auto& c : out) if ((unsigned char)c >= 0x80) c = '?';
  return env->NewStringUTF(out.c_str());
}
