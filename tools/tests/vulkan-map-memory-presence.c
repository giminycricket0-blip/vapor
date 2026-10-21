/*
 * Placed-memory capability presence fixture for the Android Vulkan loader path.
 * No game, window, GPU workload or placed mapping is performed.
 *
 * Loads a Vulkan loader (default /system/lib64/libvulkan.so) through
 * vkGetInstanceProcAddr, creates an instance and a minimal device, enumerates
 * instance/device extensions and queries VkPhysicalDeviceFeatures2 with
 * VkPhysicalDeviceMapMemoryPlacedFeaturesEXT chained (query only, never
 * enabled). Prints machine-readable capability lines and exits 0 when the
 * queries complete. This fixture is generic over API 1.0/1.1+ and over the
 * loader: it prefers core vkGetPhysicalDeviceFeatures2/Properties2 and falls
 * back to the VK_KHR_get_physical_device_properties2 entry points.
 *
 * Usage: vulkan-map-memory-presence [LIBRARY] [--print-all]
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DEFAULT_LOADER "/system/lib64/libvulkan.so"
#define MAX_PHYSICAL_DEVICES 8

static void *library;
static VkInstance instance;
static PFN_vkGetInstanceProcAddr get_proc;
static bool print_all;

static void print_version(const char *name, uint32_t version) {
   printf("%s=%u.%u.%u\n", name, VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version),
          VK_API_VERSION_PATCH(version));
}

static void fail(const char *stage, const char *detail) {
   if (detail) fprintf(stderr, "FAIL stage=%s error=%s\n", stage, detail);
   else fprintf(stderr, "FAIL stage=%s\n", stage);
   exit(1);
}

/* Enumerate instance extension names; report count and locate one. */
static bool instance_extension_available(const char *wanted) {
   PFN_vkEnumerateInstanceExtensionProperties enumerate =
      (PFN_vkEnumerateInstanceExtensionProperties)get_proc(NULL, "vkEnumerateInstanceExtensionProperties");
   if (!enumerate) return false;
   uint32_t count = 0;
   if (enumerate(NULL, &count, NULL) != VK_SUCCESS) return false;
   if (!count) return false;
   VkExtensionProperties *properties = calloc(count, sizeof(*properties));
   if (!properties) return false;
   bool found = false;
   if (enumerate(NULL, &count, properties) == VK_SUCCESS) {
      for (uint32_t i = 0; i < count; i++) {
         if (!strcmp(properties[i].extensionName, wanted)) found = true;
         if (print_all) printf("INSTANCE_EXTENSION[%u]=%s:%u\n", i, properties[i].extensionName,
                               properties[i].specVersion);
      }
   }
   free(properties);
   return found;
}

/* Enumerate device extension names; report all only with --print-all. */
static void device_extensions(PFN_vkEnumerateDeviceExtensionProperties enumerate, VkPhysicalDevice physical,
                              const char *wanted, bool *present, uint32_t *spec_version,
                              uint32_t *count_out) {
   *present = false;
   *spec_version = 0;
   *count_out = 0;
   uint32_t count = 0;
   if (enumerate(physical, NULL, &count, NULL) != VK_SUCCESS)
      fail("enumerate-device-extension-properties", NULL);
   *count_out = count;
   if (!count) return;
   VkExtensionProperties *properties = calloc(count, sizeof(*properties));
   if (!properties) fail("calloc", NULL);
   if (enumerate(physical, NULL, &count, properties) != VK_SUCCESS)
      fail("enumerate-device-extension-properties-list", NULL);
   for (uint32_t i = 0; i < count; i++) {
      if (!strcmp(properties[i].extensionName, wanted)) {
         *present = true;
         *spec_version = properties[i].specVersion;
      }
      if (print_all) printf("DEVICE_EXTENSION[%u]=%s:%u\n", i, properties[i].extensionName,
                            properties[i].specVersion);
   }
   free(properties);
}

static VkResult create_instance(uint32_t api_version, bool enable_khr_properties2) {
   static const char *khr_properties2 = "VK_KHR_get_physical_device_properties2";
   VkApplicationInfo application = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "Vapor placed-memory presence fixture", .applicationVersion = 1,
      .pEngineName = "none", .engineVersion = 1, .apiVersion = api_version};
   VkInstanceCreateInfo create = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &application};
   if (enable_khr_properties2) {
      create.enabledExtensionCount = 1;
      create.ppEnabledExtensionNames = &khr_properties2;
   }
   PFN_vkCreateInstance create_instance_fn = (PFN_vkCreateInstance)get_proc(NULL, "vkCreateInstance");
   if (!create_instance_fn) fail("GetInstanceProcAddr-vkCreateInstance", NULL);
   return create_instance_fn(&create, NULL, &instance);
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   const char *loader_path = DEFAULT_LOADER;
   for (int i = 1; i < argc; i++) {
      if (!strcmp(argv[i], "--print-all")) print_all = true;
      else if (argv[i][0] != '-') loader_path = argv[i];
      else {
         fprintf(stderr, "Usage: %s [LIBRARY] [--print-all]\n", argv[0]);
         return 2;
      }
   }

   library = dlopen(loader_path, RTLD_NOW | RTLD_LOCAL);
   if (!library) fail("dlopen", dlerror());
   get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
   const char *loader_entry = "vkGetInstanceProcAddr";
   if (!get_proc) {
      /* Do not silently accept a direct-ICD entry point: record which one is used. */
      get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vk_icdGetInstanceProcAddr");
      loader_entry = "vk_icdGetInstanceProcAddr";
   }
   if (!get_proc) fail("GetInstanceProcAddr", NULL);

   uint32_t loader_api = VK_API_VERSION_1_0;
   PFN_vkEnumerateInstanceVersion enumerate_version =
      (PFN_vkEnumerateInstanceVersion)get_proc(NULL, "vkEnumerateInstanceVersion");
   if (enumerate_version && enumerate_version(&loader_api) != VK_SUCCESS) loader_api = VK_API_VERSION_1_0;

   bool have_khr_properties2 = false;
   uint32_t instance_extension_count = 0;
   {
      PFN_vkEnumerateInstanceExtensionProperties enumerate =
         (PFN_vkEnumerateInstanceExtensionProperties)get_proc(NULL, "vkEnumerateInstanceExtensionProperties");
      if (enumerate) {
         if (enumerate(NULL, &instance_extension_count, NULL) != VK_SUCCESS) instance_extension_count = 0;
         if (instance_extension_count) have_khr_properties2 = instance_extension_available("VK_KHR_get_physical_device_properties2");
      }
   }

   /* Prefer a 1.1+ instance (core features2); clamp lower/higher API levels. */
   uint32_t instance_api = loader_api;
   if (instance_api > VK_API_VERSION_1_3) instance_api = VK_API_VERSION_1_3;
   bool needs_khr = instance_api < VK_API_VERSION_1_1 && have_khr_properties2;
   VkResult created = create_instance(instance_api, needs_khr);
   if (created != VK_SUCCESS) {
      const uint32_t fallbacks[] = {VK_API_VERSION_1_2, VK_API_VERSION_1_1, VK_API_VERSION_1_0};
      for (size_t i = 0; i < sizeof(fallbacks) / sizeof(fallbacks[0]) && created != VK_SUCCESS; i++) {
         if (fallbacks[i] >= instance_api) continue;
         instance_api = fallbacks[i];
         created = create_instance(instance_api, instance_api < VK_API_VERSION_1_1 && have_khr_properties2);
      }
   }
   if (created != VK_SUCCESS) fail("vkCreateInstance", "no supported instance API version");

   printf("PRESENCE pid=%d\n", (int)getpid());
   printf("PAGE_SIZE=%ld\n", sysconf(_SC_PAGESIZE));
   printf("LOADER_PATH=%s\n", loader_path);
   printf("LOADER_ENTRY=%s\n", loader_entry);
   print_version("LOADER_API", loader_api);
   print_version("INSTANCE_API", instance_api);
   printf("INSTANCE_EXTENSION_COUNT=%u\n", instance_extension_count);

#define LOAD_INSTANCE(name) \
   PFN_vk##name vk##name = (PFN_vk##name)get_proc(instance, "vk" #name); \
   if (!vk##name) fail("missing-" #name, NULL)
   LOAD_INSTANCE(EnumeratePhysicalDevices);
   LOAD_INSTANCE(GetPhysicalDeviceProperties);
   LOAD_INSTANCE(GetPhysicalDeviceQueueFamilyProperties);
   LOAD_INSTANCE(CreateDevice);
   LOAD_INSTANCE(DestroyDevice);
   LOAD_INSTANCE(DestroyInstance);
#undef LOAD_INSTANCE

   uint32_t device_count = 0;
   if (vkEnumeratePhysicalDevices(instance, &device_count, NULL) != VK_SUCCESS)
      fail("enumerate-physical-devices", NULL);
   printf("DEVICE_COUNT=%u\n", device_count);
   if (!device_count) fail("no-physical-device", NULL);
   if (device_count > MAX_PHYSICAL_DEVICES) device_count = MAX_PHYSICAL_DEVICES;
   VkPhysicalDevice devices[MAX_PHYSICAL_DEVICES];
   if (vkEnumeratePhysicalDevices(instance, &device_count, devices) != VK_SUCCESS)
      fail("enumerate-physical-devices-list", NULL);
   VkPhysicalDevice physical = devices[0];

   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(physical, &properties);
   printf("DEVICE_NAME=%s\n", properties.deviceName);
   printf("DEVICE_VENDOR_ID=%x\n", properties.vendorID);
   printf("DEVICE_ID=%x\n", properties.deviceID);
   print_version("DEVICE_API", properties.apiVersion);

   /* Resolve core features2/properties2 first, then the KHR fallbacks. */
   PFN_vkGetPhysicalDeviceFeatures2 features2 =
      (PFN_vkGetPhysicalDeviceFeatures2)get_proc(instance, "vkGetPhysicalDeviceFeatures2");
   const char *features2_path = "core";
   if (!features2) {
      features2 = (PFN_vkGetPhysicalDeviceFeatures2)get_proc(instance, "vkGetPhysicalDeviceFeatures2KHR");
      features2_path = "VK_KHR_get_physical_device_properties2";
   }
   PFN_vkGetPhysicalDeviceProperties2 properties2 =
      (PFN_vkGetPhysicalDeviceProperties2)get_proc(instance, "vkGetPhysicalDeviceProperties2");
   if (!properties2)
      properties2 = (PFN_vkGetPhysicalDeviceProperties2)get_proc(instance, "vkGetPhysicalDeviceProperties2KHR");
   printf("FEATURES2_PATH=%s\n", features2 ? features2_path : "unavailable");

   if (properties.apiVersion >= VK_API_VERSION_1_2 && properties2) {
      VkPhysicalDeviceDriverProperties driver = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
      VkPhysicalDeviceProperties2 chained = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
         .pNext = &driver};
      properties2(physical, &chained);
      printf("DRIVER_NAME=%s\n", driver.driverName);
      printf("DRIVER_INFO=%s\n", driver.driverInfo);
   } else {
      printf("DRIVER_NAME=unavailable\n");
      printf("DRIVER_INFO=unavailable\n");
   }

   PFN_vkEnumerateDeviceExtensionProperties enumerate_device_extensions =
      (PFN_vkEnumerateDeviceExtensionProperties)get_proc(instance, "vkEnumerateDeviceExtensionProperties");
   if (!enumerate_device_extensions) fail("missing-vkEnumerateDeviceExtensionProperties", NULL);
   bool extension_present = false;
   uint32_t extension_spec_version = 0;
   uint32_t device_extension_count = 0;
   device_extensions(enumerate_device_extensions, physical, VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME,
                     &extension_present, &extension_spec_version, &device_extension_count);
   printf("DEVICE_EXTENSION_COUNT=%u\n", device_extension_count);
   printf("EXTENSION_NAME=%s\n", VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME);
   printf("EXTENSION_PRESENT=%d\n", extension_present ? 1 : 0);
   if (extension_present) printf("EXTENSION_SPEC_VERSION=%u\n", extension_spec_version);
   else printf("EXTENSION_SPEC_VERSION=none\n");

   bool memory_map_placed = false;
   bool memory_map_range_placed = false;
   bool memory_unmap_reserve = false;
   if (features2) {
      VkPhysicalDeviceFeatures2 base = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
      features2(physical, &base);
      printf("FEATURES2_BASE_QUERY=ok\n");
      /* Positive controls: the query path is not silently returning an empty response. */
      printf("BASE_FEATURE_ROBUST_BUFFER_ACCESS=%d\n", base.features.robustBufferAccess ? 1 : 0);
      printf("BASE_FEATURE_SAMPLER_ANISOTROPY=%d\n", base.features.samplerAnisotropy ? 1 : 0);
      printf("BASE_FEATURE_TEXTURE_COMPRESSION_BC=%d\n", base.features.textureCompressionBC ? 1 : 0);
      VkPhysicalDeviceMapMemoryPlacedFeaturesEXT placed = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
      VkPhysicalDeviceFeatures2 chained = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
         .pNext = &placed};
      features2(physical, &chained);
      memory_map_placed = placed.memoryMapPlaced == VK_TRUE;
      memory_map_range_placed = placed.memoryMapRangePlaced == VK_TRUE;
      memory_unmap_reserve = placed.memoryUnmapReserve == VK_TRUE;
      printf("FEATURES2_PLACED_QUERY=ok\n");
   } else {
      printf("FEATURES2_PLACED_QUERY=unavailable\n");
   }
   printf("MEMORY_MAP_PLACED=%d\n", memory_map_placed ? 1 : 0);
   printf("MEMORY_MAP_RANGE_PLACED=%d\n", memory_map_range_placed ? 1 : 0);
   printf("MEMORY_UNMAP_RESERVE=%d\n", memory_unmap_reserve ? 1 : 0);

   if (extension_present && properties2) {
      VkPhysicalDeviceMapMemoryPlacedPropertiesEXT placed_properties = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT};
      VkPhysicalDeviceProperties2 chained = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
         .pNext = &placed_properties};
      properties2(physical, &chained);
      printf("PLACED_MIN_MAP_ALIGNMENT=%llu\n",
             (unsigned long long)placed_properties.minPlacedMemoryMapAlignment);
   } else {
      printf("PLACED_MIN_MAP_ALIGNMENT=none\n");
   }

   /* The capability gate the loader-path harness uses: SKIP unless advertised and true. */
   if (extension_present && memory_map_placed) {
      printf("CAPABILITY=placed-memory-capable\n");
      printf("RESULT=SUPPORTED\n");
      printf("REASON=extension-advertised-and-memoryMapPlaced-true\n");
   } else if (!extension_present) {
      printf("CAPABILITY=absent\n");
      printf("RESULT=SKIP\n");
      printf("REASON=extension-not-advertised\n");
   } else {
      printf("CAPABILITY=advertised-feature-false\n");
      printf("RESULT=SKIP\n");
      printf("REASON=memoryMapPlaced-false\n");
   }

   /* Minimal, feature-less device creation: proves the loader path reaches a device. */
   uint32_t family_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, NULL);
   if (!family_count) {
      printf("DEVICE_CREATE=skipped-no-queue-family\n");
   } else {
      VkQueueFamilyProperties *families = calloc(family_count, sizeof(*families));
      if (!families) fail("calloc", NULL);
      vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, families);
      uint32_t family = UINT32_MAX;
      for (uint32_t i = 0; i < family_count; i++)
         if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
             (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            family = i;
            break;
         }
      if (family == UINT32_MAX)
         for (uint32_t i = 0; i < family_count; i++)
            if (families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
               family = i;
               break;
            }
      if (family == UINT32_MAX) family = 0;
      free(families);
      float priority = 1.0f;
      VkDeviceQueueCreateInfo queue = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
         .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
      VkDeviceCreateInfo create = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
         .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue};
      VkDevice device;
      VkResult result = vkCreateDevice(physical, &create, NULL, &device);
      if (result == VK_SUCCESS) {
         printf("DEVICE_CREATE=ok\n");
         printf("QUEUE_FAMILY_INDEX=%u\n", family);
         vkDestroyDevice(device, NULL);
      } else {
         printf("DEVICE_CREATE=skipped\n");
         printf("DEVICE_CREATE_RESULT=%d\n", (int)result);
      }
   }

   vkDestroyInstance(instance, NULL);
   dlclose(library);
   printf("CLEANUP=ok\n");
   return 0;
}
