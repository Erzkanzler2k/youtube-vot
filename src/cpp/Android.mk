LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := byedpi
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi $(LOCAL_PATH)
LOCAL_SRC_FILES := $(filter-out byedpi/win_service.c,$(wildcard byedpi/*.c)) native-lib.c
LOCAL_CFLAGS := -O3 -std=c99 -DANDROID_APP -Wall -Wextra
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

# hev-socks5-tunnel (tun2socks) НЕ подключается здесь: его Android.mk
# использует пути вида src/core/..., валидные только относительно его
# собственной папки. При include отсюда ndk-build падает с
# "No rule to make target 'src/core/src/hev-rbtree.c'".
# Его собирает отдельный вызов ndk-build -C hev-socks5-tunnel в build.ps1.
