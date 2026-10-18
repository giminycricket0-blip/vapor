#include <vulkan/vulkan.h>

#include "../adrenotools/include/adrenotools/driver.h"
#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

VkInstance instance;
VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;

static void destroy_probe_instance(void) {
  if (instance != VK_NULL_HANDLE) {
    PFN_vkDestroyInstance destroy = destroyInstance;
    if (destroy == NULL && vulkan_handle != NULL) {
      destroy = (PFN_vkDestroyInstance)dlsym(vulkan_handle, "vkDestroyInstance");
    }
    if (destroy != NULL) destroy(instance, NULL);
  }
  instance = VK_NULL_HANDLE;
  physicalDevice = VK_NULL_HANDLE;
  getPhysicalDeviceProperties = NULL;
  enumerateDeviceExtensionProperties = NULL;
  enumeratePhysicalDevices = NULL;
  destroyInstance = NULL;
  if (vulkan_handle) {
    dlclose(vulkan_handle);
    vulkan_handle = NULL;
  }
}

static char *get_native_library_dir(JNIEnv *env, jobject context) {
  char *native_libdir = NULL;

  if (context == NULL)
    return NULL;

  jclass class_ =
      (*env)->FindClass(env, "com/winlator/cmod/shared/android/AppUtils");
  if (class_ == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(
      env, class_, "getNativeLibDir",
      "(Landroid/content/Context;)Ljava/lang/String;");
  if (getNativeLibraryDir == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(
      env, class_, getNativeLibraryDir, context);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (nativeLibDir) {
    const char *utf = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    if (utf) {
      native_libdir = strdup(utf);
      (*env)->ReleaseStringUTFChars(env, nativeLibDir, utf);
    }
  }

  return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context,
                             const char *driver_name) {
  char *driver_path = NULL;
  char *absolute_path;

  if (context == NULL)
    return NULL;

  jclass contextClass =
      (*env)->FindClass(env, "android/content/Context");
  if (contextClass == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getFilesDir = (*env)->GetMethodID(
      env, contextClass, "getFilesDir", "()Ljava/io/File;");
  if (getFilesDir == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jobject filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  if (filesDirObj == NULL)
    return NULL;

  jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
  if (fileClass == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID getAbsolutePath = (*env)->GetMethodID(
      env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
  if (getAbsolutePath == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring absolutePath =
      (jstring)(*env)->CallObjectMethod(env, filesDirObj, getAbsolutePath);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (absolutePath) {
    absolute_path = (char *)(*env)->GetStringUTFChars(env, absolutePath, NULL);
    asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path,
             driver_name);
    (*env)->ReleaseStringUTFChars(env, absolutePath, absolute_path);
  }

  return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context,
                              const char *driver_name) {
  char *library_name = NULL;

  if (context == NULL)
    return NULL;

  jclass adrenotoolsManager = (*env)->FindClass(
      env, "com/winlator/cmod/runtime/content/AdrenotoolsManager");
  if (adrenotoolsManager == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>",
                                              "(Landroid/content/Context;)V");
  if (constructor == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jobject adrenotoolsManagerObj =
      (*env)->NewObject(env, adrenotoolsManager, constructor, context);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  if (adrenotoolsManagerObj == NULL)
    return NULL;

  jmethodID getLibraryName =
      (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName",
                          "(Ljava/lang/String;)Ljava/lang/String;");
  if (getLibraryName == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  jstring driverName = (*env)->NewStringUTF(env, driver_name);
  jstring libraryName = (jstring)(*env)->CallObjectMethod(
      env, adrenotoolsManagerObj, getLibraryName, driverName);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }

  if (libraryName) {
    const char *utf = (*env)->GetStringUTFChars(env, libraryName, NULL);
    if (utf) {
      library_name = strdup(utf);
      (*env)->ReleaseStringUTFChars(env, libraryName, utf);
    }
  }

  return library_name;
}

static void preload_first_existing(const char **candidates) {
  for (int i = 0; candidates[i]; i++) {
    if (dlopen(candidates[i], RTLD_GLOBAL | RTLD_NOW))
      return;
  }
}

static void preload_vendor_icd_deps() {
  // Keep OEM Vulkan ICD dependencies globally visible before the loader pulls
  // them in, or DXVK can misreport missing VK_KHR_surface.
  const char *jpeg_candidates[] = {
      "/system/lib64/libjpeg.so",
      "/system_ext/lib64/libjpeg.so",
      "libjpeg.so",
      NULL,
  };
  preload_first_existing(jpeg_candidates);

  const char *crypto_candidates[] = {
      "libcrypto.so",
      NULL,
  };
  preload_first_existing(crypto_candidates);
}

void *winlator_open_system_vulkan(void) {
  preload_vendor_icd_deps();
  return dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
}

static void *open_custom_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
  preload_vendor_icd_deps();

  char *driver_path = get_driver_path(env, context, driver_name);
  if (!driver_path || access(driver_path, F_OK) != 0) {
    free(driver_path);
    return NULL;
  }

  char *library_name = get_library_name(env, context, driver_name);
  char *native_library_dir = get_native_library_dir(env, context);
  char *tmpdir = NULL;
  void *handle = NULL;
  if (library_name && native_library_dir) {
    asprintf(&tmpdir, "%s%s", driver_path, "temp");
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW,
        ADRENOTOOLS_DRIVER_CUSTOM, tmpdir,
        native_library_dir, driver_path, library_name, NULL,
        NULL);
  }
  free(tmpdir);
  free(native_library_dir);
  free(library_name);
  free(driver_path);
  return handle;
}

void *winlator_open_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
  if (!driver_name || strcmp(driver_name, "System") == 0) {
    return winlator_open_system_vulkan();
  }
  void *handle = open_custom_vulkan(env, context, driver_name);
  return handle ? handle : winlator_open_system_vulkan();
}

static void init_original_vulkan() {
  vulkan_handle = winlator_open_system_vulkan();
}

static void init_vulkan(JNIEnv *env, jobject context, const char *driver_name) {
  // A probe must describe the requested driver, never a successful system fallback.
  vulkan_handle = open_custom_vulkan(env, context, driver_name);
}

static VkResult create_probe_instance(jstring driverName, JNIEnv *env,
                                     jobject context, int query_features) {
  VkResult result;
  VkInstanceCreateInfo create_info = {};
  const char *driver_name = NULL;

  if (driverName != NULL)
    driver_name = (*env)->GetStringUTFChars(env, driverName, NULL);

  if (driver_name && strcmp(driver_name, "System"))
    init_vulkan(env, context, driver_name);
  else
    init_original_vulkan();

  if (driver_name)
    (*env)->ReleaseStringUTFChars(env, driverName, driver_name);

  if (!vulkan_handle)
    return VK_ERROR_INITIALIZATION_FAILED;

  PFN_vkGetInstanceProcAddr gip =
      (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
  PFN_vkCreateInstance createInstance =
      (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
  PFN_vkEnumerateInstanceVersion enumerateInstanceVersion =
      (PFN_vkEnumerateInstanceVersion)dlsym(vulkan_handle,
                                            "vkEnumerateInstanceVersion");

  if (!gip || !createInstance || !enumerateInstanceVersion)
    return VK_ERROR_INITIALIZATION_FAILED;

  int apiLevel = android_get_device_api_level();

  VkApplicationInfo app_info = {};
  app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  app_info.pApplicationName = "WinNative";
  app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
  app_info.pEngineName = "WinNative";
  app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
  if (query_features) {
    uint32_t loader_version = VK_API_VERSION_1_0;
    if (enumerateInstanceVersion(&loader_version) != VK_SUCCESS ||
        loader_version < VK_API_VERSION_1_1) return VK_ERROR_INCOMPATIBLE_DRIVER;
    // Features2 is core in 1.1. Leave the older renderer/name probes unchanged.
    app_info.apiVersion = VK_API_VERSION_1_1;
  } else if (apiLevel > 32)
    app_info.apiVersion = VK_API_VERSION_1_0;
  else
    enumerateInstanceVersion(&app_info.apiVersion);

  create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  create_info.pNext = NULL;
  create_info.flags = 0;
  create_info.pApplicationInfo = &app_info;
  create_info.enabledLayerCount = 0;
  create_info.enabledExtensionCount = 0;

  result = createInstance(&create_info, NULL, &instance);

  if (result != VK_SUCCESS)
    return result;

  getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(
      instance, "vkGetPhysicalDeviceProperties");
  destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
  enumerateDeviceExtensionProperties =
      (PFN_vkEnumerateDeviceExtensionProperties)gip(
          instance, "vkEnumerateDeviceExtensionProperties");
  enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(
      instance, "vkEnumeratePhysicalDevices");

  if (!getPhysicalDeviceProperties || !destroyInstance ||
      !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices)
    return VK_ERROR_INITIALIZATION_FAILED;

  return VK_SUCCESS;
}

static VkResult create_instance(jstring driverName, JNIEnv *env, jobject context) {
  return create_probe_instance(driverName, env, context, 0);
}

static VkResult enumerate_physical_devices() {
  VkResult result;
  uint32_t deviceCount = 0;

  result = enumeratePhysicalDevices(instance, &deviceCount, NULL);

  if (result != VK_SUCCESS)
    return result;

  if (deviceCount < 1)
    return VK_ERROR_INITIALIZATION_FAILED;

  VkPhysicalDevice *pdevices = malloc(sizeof(VkPhysicalDevice) * deviceCount);
  if (!pdevices)
    return VK_ERROR_OUT_OF_HOST_MEMORY;

  result = enumeratePhysicalDevices(instance, &deviceCount, pdevices);

  if (result != VK_SUCCESS) {
    free(pdevices);
    return result;
  }

  physicalDevice = pdevices[0];
  free(pdevices);

  if (physicalDevice == VK_NULL_HANDLE)
    return VK_ERROR_INITIALIZATION_FAILED;

  return VK_SUCCESS;
}

// A single fresh snapshot, serialized by GPUInformation.PROBE_LOCK. Extension names
// alone do not establish that a driver's feature bits are enabled/supported.
JNIEXPORT jlongArray JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_probeCapabilitiesNative(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  (void)obj;
  jlong data[7] = {0};
  VkExtensionProperties *extensions = NULL;
  if (create_probe_instance(driverName, env, context, 1) != VK_SUCCESS ||
      enumerate_physical_devices() != VK_SUCCESS) goto done;
  PFN_vkGetInstanceProcAddr gip =
      (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
  PFN_vkGetPhysicalDeviceFeatures2 features2 =
      (PFN_vkGetPhysicalDeviceFeatures2)gip(instance, "vkGetPhysicalDeviceFeatures2");
  PFN_vkGetPhysicalDeviceProperties2 properties2 =
      (PFN_vkGetPhysicalDeviceProperties2)gip(instance, "vkGetPhysicalDeviceProperties2");
  if (!features2 || !properties2) goto done;

  VkPhysicalDeviceProperties props = {0};
  getPhysicalDeviceProperties(physicalDevice, &props);
  uint32_t count = 0;
  if (enumerateDeviceExtensionProperties(physicalDevice, NULL, &count, NULL) != VK_SUCCESS ||
      count == 0 || count > 4096) goto done;
  extensions = calloc(count, sizeof(*extensions));
  if (!extensions || enumerateDeviceExtensionProperties(physicalDevice, NULL, &count, extensions) != VK_SUCCESS)
    goto done;
  int robustness = 0, swapchain = 0, push_descriptor = 0;
  for (uint32_t i = 0; i < count; ++i) {
    if (!strcmp(extensions[i].extensionName, "VK_EXT_robustness2")) robustness = 1;
    if (!strcmp(extensions[i].extensionName, "VK_KHR_swapchain")) swapchain = 1;
    if (!strcmp(extensions[i].extensionName, "VK_KHR_push_descriptor")) push_descriptor = 1;
  }
  VkPhysicalDeviceFeatures2 f = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
  VkPhysicalDeviceVulkan11Features f11 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
  VkPhysicalDeviceVulkan12Features f12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
  VkPhysicalDeviceVulkan13Features f13 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
  VkPhysicalDeviceRobustness2FeaturesEXT r2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT};
  // The aggregate 1.1 feature structure was introduced by Vulkan 1.2.
  if (props.apiVersion >= VK_API_VERSION_1_2) {
    f.pNext = &f11;
    f11.pNext = &f12;
    if (props.apiVersion >= VK_API_VERSION_1_3) { f12.pNext = &f13; }
  }
  if (robustness) { r2.pNext = f.pNext; f.pNext = &r2; }
  features2(physicalDevice, &f);
  VkPhysicalDeviceVulkan12Properties p12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
  VkPhysicalDeviceProperties2 p = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
  if (props.apiVersion >= VK_API_VERSION_1_2) p.pNext = &p12;
  properties2(physicalDevice, &p);

  uint64_t flags = 0;
  if (f.features.textureCompressionBC) flags |= 1ULL << 0;
  if (f12.timelineSemaphore) flags |= 1ULL << 1;
  if (f12.runtimeDescriptorArray && f12.descriptorBindingPartiallyBound &&
      f12.descriptorBindingSampledImageUpdateAfterBind &&
      f12.descriptorBindingStorageImageUpdateAfterBind &&
      f12.descriptorBindingStorageBufferUpdateAfterBind &&
      f12.descriptorBindingUniformTexelBufferUpdateAfterBind &&
      f12.descriptorBindingStorageTexelBufferUpdateAfterBind &&
      f12.shaderSampledImageArrayNonUniformIndexing && f12.shaderStorageImageArrayNonUniformIndexing &&
      f12.shaderStorageBufferArrayNonUniformIndexing &&
      f12.shaderUniformTexelBufferArrayNonUniformIndexing && f12.shaderStorageTexelBufferArrayNonUniformIndexing)
    flags |= 1ULL << 2;
  if (f13.dynamicRendering) flags |= 1ULL << 3;
  if (r2.robustBufferAccess2 && r2.nullDescriptor) flags |= 1ULL << 4;
  if (f11.shaderDrawParameters && f12.samplerMirrorClampToEdge) flags |= 1ULL << 5;
  if (p12.maxPerStageDescriptorUpdateAfterBindSamplers >= 1000000 &&
      p12.maxPerStageDescriptorUpdateAfterBindSampledImages >= 1000000 &&
      p12.maxPerStageDescriptorUpdateAfterBindStorageImages >= 1000000 &&
      p12.maxPerStageDescriptorUpdateAfterBindStorageBuffers >= 1000000 &&
      p12.maxDescriptorSetUpdateAfterBindSamplers >= 1000000 &&
      p12.maxDescriptorSetUpdateAfterBindSampledImages >= 1000000 &&
      p12.maxDescriptorSetUpdateAfterBindStorageImages >= 1000000 &&
      p12.maxDescriptorSetUpdateAfterBindStorageBuffers >= 1000000) flags |= 1ULL << 6;
  if (swapchain) flags |= 1ULL << 7;
  if (push_descriptor) flags |= 1ULL << 8;
  if (f13.synchronization2 && f12.bufferDeviceAddress) flags |= 1ULL << 9;
  data[0] = props.apiVersion;
  data[1] = props.vendorID;
  data[2] = props.deviceID;
  data[3] = props.driverVersion;
  data[4] = (jlong)flags;
  memcpy(&data[5], props.pipelineCacheUUID, VK_UUID_SIZE);
done:
  free(extensions);
  destroy_probe_instance();
  jlongArray result = (*env)->NewLongArray(env, 7);
  if (result) (*env)->SetLongArrayRegion(env, result, 0, 7, data);
  return result;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getVulkanVersionNative(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};
  char *driverVersion;

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    destroy_probe_instance();
    return (*env)->NewStringUTF(env, "Unknown");
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    destroy_probe_instance();
    return (*env)->NewStringUTF(env, "Unknown");
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  uint32_t api_version_major = VK_VERSION_MAJOR(props.apiVersion);
  uint32_t api_version_minor = VK_VERSION_MINOR(props.apiVersion);
  uint32_t api_version_patch = VK_VERSION_PATCH(props.apiVersion);
  asprintf(&driverVersion, "%d.%d.%d", api_version_major, api_version_minor,
           api_version_patch);

  jstring result = (*env)->NewStringUTF(env, driverVersion);
  free(driverVersion);

  destroy_probe_instance();

  return result;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getVendorIDNative(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};
  uint32_t vendorID;

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    destroy_probe_instance();
    return 0;
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    destroy_probe_instance();
    return 0;
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  vendorID = props.vendorID;

  destroy_probe_instance();

  return vendorID;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_getRendererNative(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  VkPhysicalDeviceProperties props = {};

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    destroy_probe_instance();
    return (*env)->NewStringUTF(env, "Unknown");
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    destroy_probe_instance();
    return (*env)->NewStringUTF(env, "Unknown");
  }

  getPhysicalDeviceProperties(physicalDevice, &props);
  jstring result = (*env)->NewStringUTF(env, props.deviceName);

  destroy_probe_instance();

  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_cmod_runtime_system_GPUInformation_enumerateExtensionsNative(
    JNIEnv *env, jclass obj, jstring driverName, jobject context) {
  jobjectArray extensions;
  VkResult result;
  uint32_t extensionCount;
  jclass stringClass = (*env)->FindClass(env, "java/lang/String");

  if (create_instance(driverName, env, context) != VK_SUCCESS) {
    printf("Failed to create instance");
    destroy_probe_instance();
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  if (enumerate_physical_devices() != VK_SUCCESS) {
    printf("Failed to query physical devices");
    destroy_probe_instance();
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  result = enumerateDeviceExtensionProperties(physicalDevice, NULL,
                                              &extensionCount, NULL);

  if (result != VK_SUCCESS || extensionCount < 1) {
    printf("Failed to query extension count");
    destroy_probe_instance();
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  VkExtensionProperties *extensionProperties =
      malloc(sizeof(VkExtensionProperties) * extensionCount);
  if (!extensionProperties) {
    printf("Failed to allocate extension properties");
    destroy_probe_instance();
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  result = enumerateDeviceExtensionProperties(
      physicalDevice, NULL, &extensionCount, extensionProperties);

  if (result != VK_SUCCESS) {
    printf("Failed to query extensions (result=%d)", result);
    free(extensionProperties);
    destroy_probe_instance();
    return (*env)->NewObjectArray(env, 0, stringClass, NULL);
  }

  extensions = (jobjectArray)(*env)->NewObjectArray(env, extensionCount,
                                                    stringClass, NULL);
  for (uint32_t i = 0; i < extensionCount; i++) {
    jstring extName =
        (*env)->NewStringUTF(env, extensionProperties[i].extensionName);
    (*env)->SetObjectArrayElement(env, extensions, i, extName);
    (*env)->DeleteLocalRef(env, extName);
  }

  free(extensionProperties);

  destroy_probe_instance();

  return extensions;
}
