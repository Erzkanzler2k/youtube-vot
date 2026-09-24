#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "error.h"
#include "main.h"
#include "params.h"
#include "proxy.h"

extern int NOT_EXIT;

JNIEXPORT jint JNICALL
Java_com_vot_youtube_ByeDpiNative_createSocketWithCommandLine(JNIEnv *env, jobject thiz,
                                                               jobjectArray args) {
    (void)thiz;
    jsize argc = (*env)->GetArrayLength(env, args);
    char **argv = (char **)calloc((size_t)argc, sizeof(char *));
    if (argv == NULL) return -1;
    for (jsize i = 0; i < argc; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        argv[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
    }
    int result = parse_args((int)argc, argv);
    if (result < 0) return -1;
    int fd = listen_socket((struct sockaddr_ina *)&params.laddr);
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_vot_youtube_ByeDpiNative_startProxy(JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    NOT_EXIT = 1;
    if (event_loop((int)fd) < 0) return get_e();
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_vot_youtube_ByeDpiNative_stopProxy(JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    shutdown((int)fd, SHUT_RDWR);
    clear_params();
    return 0;
}
