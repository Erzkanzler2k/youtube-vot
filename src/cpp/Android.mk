LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := byedpi
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi $(LOCAL_PATH)
LOCAL_SRC_FILES := $(filter-out byedpi/win_service.c,$(wildcard byedpi/*.c)) native-lib.c
LOCAL_CFLAGS := -O3 -std=c99 -DANDROID_APP -Wall -Wextra
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

# hev-socks5-tunnel (tun2socks) — вторая половина транспорта обхода.
# Без неё BypassVpnService.isReady() == false, потому что TProxyService
# грузит именно libhev-socks5-tunnel.so, и переключатель обхода отвечает
# «движок не готов». Модуль собирает собственный Android.mk, подключаем его.
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk
