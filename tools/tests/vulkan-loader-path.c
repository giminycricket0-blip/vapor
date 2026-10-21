/* Loader-mediated Vulkan placed-memory probe (headless, no GPU assumptions).
 *
 * Part of the Vapor "no-GPU loader-path" CI lane.  This harness deliberately
 * goes through the Vulkan loader (it links against libvulkan and never dlopens
 * an ICD itself), so it exercises loader enumeration, the device-extension list
 * and the VkPhysicalDeviceMapMemoryPlacedFeaturesEXT chain.  Two lanes use it:
 *
 *   - Lane A (real software ICD, e.g. lavapipe): the extension is absent, the
 *     harness must report RESULT=SKIP and exit 77.  CI asserts no validation
 *     errors on this path.
 *   - Lane B (Vulkan-Tools Mock ICD with the documented capability patch, or
 *     mock ICD + Vulkan-Profiles layer when available): both placed-memory
 *     feature bits are advertised, so the harness creates a device with the
 *     extension + features enabled, allocates host-visible memory, maps it with
 *     vkMapMemory2 + VkMemoryMapPlacedInfoEXT (placed and range-placed), unmaps
 *     with vkUnmapMemory2 and tears everything down.
 *
 * Real RADV/ANV/NVK/Turnip semantics are NOT tested here: the mock ICD ignores
 * the placement address and lavapipe does not implement the extension.  The
 * lane validates loader/entry-point plumbing, capability reporting and the
 * skip logic only.
 *
 * Usage:
 *   vulkan-loader-path [--expect skip|supported] [--validation] [--layers]
 *                      [--print-all] [--require-extension]
 *
 * Options:
 *   --expect skip|supported  required capability outcome (default: supported).
 *                            0 = expectation met, 77 = SKIP, 1 = mismatch/failure.
 *   --validation             enable VK_LAYER_KHRONOS_validation and fail on any
 *                            ERROR message (VUIDs included). If the layer or
 *                            VK_EXT_debug_utils is unavailable the harness fails
 *                            instead of silently passing without validation.
 *   --layers                 print available instance layers.
 *   --print-all              dump every device extension reported by the loader.
 *   --require-extension      fail instead of skip when VK_EXT_map_memory_placed is
 *                            missing (useful to catch a mis-selected ICD).
 *   --help
 *
 * Machine-readable output (stdout, one KEY=value per line):
 *   HARNESS=vulkan-loader-path
 *   INSTANCE_API=x.y.z
 *   VALIDATION_REQUESTED=0|1 VALIDATION_AVAILABLE=0|1 VALIDATION_ERRORS=n VUID_ERRORS=n
 *   INSTANCE_LAYER=<name>            (one line per available layer)
 *   PHYSICAL_DEVICES=n DEVICE_NAME=... DEVICE_TYPE=... DEVICE_API=x.y.z
 *   EXTENSION_PRESENT=0|1 EXTENSION_NAME=VK_EXT_map_memory_placed
 *   MEMORY_MAP_PLACED=0|1 MEMORY_MAP_RANGE_PLACED=0|1 MEMORY_UNMAP_RESERVE=0|1
 *   DEVICE_EXTENSION=<name>          (with --print-all)
 *   DEVICE_CREATED=0|1
 *   PLACED_MAP_RESULT=<VkResult> PLACED_RANGE_MAP_RESULT=<VkResult>
 *   UNMAP2_RESULT=<VkResult>
 *   EXPECT=skip|supported RESULT=PASS|SKIP|FAIL
 *   SKIP_REASON=... FAIL_REASON=...
 * Exit code: 0 expectation met, 77 SKIP observed, 1 failure/mismatch, 2 usage.
 */
#define _POSIX_C_SOURCE 200112L

#include <vulkan/vulkan.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
   bool expect_skip;
   bool validation;
   bool list_layers;
   bool print_all;
   bool require_extension;
   bool expect_given;
} HarnessOptions;

typedef struct {
   unsigned errors;
   unsigned vuid_errors;
   unsigned warnings;
   unsigned printed;
} ValidationState;

static HarnessOptions options;
static ValidationState validation_state;

static VKAPI_ATTR VkBool32 VKAPI_CALL debug_callback(
      VkDebugUtilsMessageSeverityFlagBitsEXT severity,
      VkDebugUtilsMessageTypeFlagsEXT types,
      const VkDebugUtilsMessengerCallbackDataEXT *callback_data,
      void *user_data) {
   (void)severity;
   (void)types;
   (void)user_data;
   const char *message = callback_data && callback_data->pMessage
                            ? callback_data->pMessage : "(no message)";
   bool is_error = severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
   bool has_vuid = strstr(message, "VUID") != NULL;
   if (is_error) {
      validation_state.errors++;
      if (has_vuid) validation_state.vuid_errors++;
      printf("VALIDATION_ERROR=%s\n", message);
   } else if (severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
      validation_state.warnings++;
      if (validation_state.printed < 20)
         printf("VALIDATION_WARNING=%s\n", message);
   }
   if (is_error) validation_state.printed++;
   return VK_FALSE;
}

static void usage(const char *argv0) {
   fprintf(stderr,
      "Usage: %s [--expect skip|supported] [--validation] [--layers]\n"
      "          [--print-all] [--require-extension] [--help]\n",
      argv0);
}

static int parse_options(int argc, char **argv) {
   memset(&options, 0, sizeof(options));
   for (int i = 1; i < argc; i++) {
      if (!strcmp(argv[i], "--expect")) {
         if (i + 1 >= argc) return 2;
         const char *value = argv[++i];
         if (!strcmp(value, "skip")) {
            options.expect_skip = true;
         } else if (!strcmp(value, "supported")) {
            options.expect_skip = false;
         } else {
            fprintf(stderr, "invalid --expect value: %s\n", value);
            return 2;
         }
         options.expect_given = true;
      } else if (!strcmp(argv[i], "--validation")) {
         options.validation = true;
      } else if (!strcmp(argv[i], "--layers")) {
         options.list_layers = true;
      } else if (!strcmp(argv[i], "--print-all")) {
         options.print_all = true;
      } else if (!strcmp(argv[i], "--require-extension")) {
         options.require_extension = true;
      } else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
         usage(argv[0]);
         return 3;
      } else {
         fprintf(stderr, "unknown option: %s\n", argv[i]);
         return 2;
      }
   }
   return 0;
}

static const char *result_string(VkResult result) {
   switch (result) {
   case VK_SUCCESS: return "VK_SUCCESS";
   case VK_NOT_READY: return "VK_NOT_READY";
   case VK_TIMEOUT: return "VK_TIMEOUT";
   case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
   case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
   case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
   case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
   case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
   case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
   case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
   case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
   case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
   case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
   default: return "VK_ERROR_OTHER";
   }
}

static bool instance_extension_available(const char *name) {
   uint32_t count = 0;
   if (vkEnumerateInstanceExtensionProperties(NULL, &count, NULL) != VK_SUCCESS) return false;
   VkExtensionProperties *extensions = calloc(count ? count : 1, sizeof(*extensions));
   if (!extensions) return false;
   bool found = false;
   if (vkEnumerateInstanceExtensionProperties(NULL, &count, extensions) == VK_SUCCESS) {
      for (uint32_t i = 0; i < count; i++)
         if (!strcmp(extensions[i].extensionName, name)) found = true;
   }
   free(extensions);
   return found;
}

static bool instance_layer_available(const char *wanted, bool print) {
   uint32_t count = 0;
   if (vkEnumerateInstanceLayerProperties(&count, NULL) != VK_SUCCESS) return false;
   VkLayerProperties *layers = calloc(count ? count : 1, sizeof(*layers));
   if (!layers) return false;
   bool found = false;
   if (vkEnumerateInstanceLayerProperties(&count, layers) == VK_SUCCESS) {
      for (uint32_t i = 0; i < count; i++) {
         if (print) printf("INSTANCE_LAYER=%s\n", layers[i].layerName);
         if (!strcmp(layers[i].layerName, wanted)) found = true;
      }
   }
   free(layers);
   return found;
}

static int finish(const char *result, const char *reason, int exit_code) {
   if (options.validation) {
      printf("VALIDATION_ERRORS=%u\n", validation_state.errors);
      printf("VUID_ERRORS=%u\n", validation_state.vuid_errors);
   }
   if (reason && *reason) printf("REASON=%s\n", reason);
   printf("RESULT=%s\n", result);
   fflush(stdout);
   if (options.validation && validation_state.errors > 0 && strcmp(result, "FAIL") != 0) {
      fprintf(stderr, "validation reported %u error message(s)\n", validation_state.errors);
      return 1;
   }
   return exit_code;
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   int parsed = parse_options(argc, argv);
   if (parsed == 3) return 0; /* --help */
   if (parsed != 0) return 2;

   printf("HARNESS=vulkan-loader-path\n");
   printf("EXPECT=%s\n", options.expect_skip ? "skip" : "supported");
   printf("VALIDATION_REQUESTED=%d\n", options.validation ? 1 : 0);

   uint32_t loader_version = 0;
   if (vkEnumerateInstanceVersion(&loader_version) == VK_SUCCESS)
      printf("INSTANCE_API=%u.%u.%u\n", VK_API_VERSION_MAJOR(loader_version),
             VK_API_VERSION_MINOR(loader_version), VK_API_VERSION_PATCH(loader_version));
   else
      printf("INSTANCE_API=1.0.0\n");

   bool debug_utils = instance_extension_available(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
   bool validation_layer = false;
   if (options.validation) {
      validation_layer = instance_layer_available("VK_LAYER_KHRONOS_validation",
                                                  options.list_layers);
      printf("VALIDATION_AVAILABLE=%d\n", validation_layer ? 1 : 0);
      if (!validation_layer) return finish("FAIL", "validation layer not found", 1);
      if (!debug_utils) return finish("FAIL", "VK_EXT_debug_utils not available", 1);
   } else if (options.list_layers) {
      instance_layer_available("", true);
   }

   VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
   PFN_vkCreateDebugUtilsMessengerEXT create_messenger = NULL;
   PFN_vkDestroyDebugUtilsMessengerEXT destroy_messenger = NULL;

   const char *enabled_layers[1];
   uint32_t enabled_layer_count = 0;
   if (options.validation) {
      enabled_layers[enabled_layer_count++] = "VK_LAYER_KHRONOS_validation";
   }
   const char *enabled_instance_extensions[1];
   uint32_t enabled_instance_extension_count = 0;
   if (options.validation && debug_utils) {
      enabled_instance_extensions[enabled_instance_extension_count++] =
         VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
   }

   VkApplicationInfo app_info = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "Vapor loader-path placed-memory probe",
      .applicationVersion = 1,
      .pEngineName = "Vapor",
      .engineVersion = 1,
      .apiVersion = VK_API_VERSION_1_3,
   };
   VkInstanceCreateInfo instance_info = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app_info,
      .enabledLayerCount = enabled_layer_count,
      .ppEnabledLayerNames = enabled_layer_count ? enabled_layers : NULL,
      .enabledExtensionCount = enabled_instance_extension_count,
      .ppEnabledExtensionNames = enabled_instance_extension_count
                                    ? enabled_instance_extensions : NULL,
   };
   VkInstance instance = VK_NULL_HANDLE;
   VkResult result = vkCreateInstance(&instance_info, NULL, &instance);
   if (result != VK_SUCCESS) {
      fprintf(stderr, "vkCreateInstance failed: %s (%d)\n", result_string(result),
              (int)result);
      return finish("FAIL", "vkCreateInstance failed", 1);
   }

   if (options.validation) {
      create_messenger = (PFN_vkCreateDebugUtilsMessengerEXT)
         vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT");
      destroy_messenger = (PFN_vkDestroyDebugUtilsMessengerEXT)
         vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT");
      if (!create_messenger || !destroy_messenger) {
         vkDestroyInstance(instance, NULL);
         return finish("FAIL", "debug-utils messenger entry points missing", 1);
      }
      VkDebugUtilsMessengerCreateInfoEXT messenger_info = {
         .sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
         .messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT,
         .messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT,
         .pfnUserCallback = debug_callback,
      };
      result = create_messenger(instance, &messenger_info, NULL, &messenger);
      if (result != VK_SUCCESS) {
         vkDestroyInstance(instance, NULL);
         return finish("FAIL", "vkCreateDebugUtilsMessengerEXT failed", 1);
      }
   }

   uint32_t device_count = 0;
   result = vkEnumeratePhysicalDevices(instance, &device_count, NULL);
   if (result != VK_SUCCESS || device_count == 0) {
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "no Vulkan physical devices visible through the loader", 1);
   }
   VkPhysicalDevice *devices = calloc(device_count, sizeof(*devices));
   if (!devices) {
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "out of host memory", 1);
   }
   result = vkEnumeratePhysicalDevices(instance, &device_count, devices);
   if (result != VK_SUCCESS) {
      free(devices);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "vkEnumeratePhysicalDevices failed", 1);
   }
   printf("PHYSICAL_DEVICES=%u\n", device_count);
   VkPhysicalDevice physical = devices[0];
   free(devices);

   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(physical, &properties);
   printf("DEVICE_NAME=%s\n", properties.deviceName);
   printf("DEVICE_TYPE=%u\n", (unsigned)properties.deviceType);
   printf("DEVICE_API=%u.%u.%u\n", VK_API_VERSION_MAJOR(properties.apiVersion),
          VK_API_VERSION_MINOR(properties.apiVersion),
          VK_API_VERSION_PATCH(properties.apiVersion));

   bool extension_present = false;
   bool map_memory2_present = false;
   uint32_t extension_count = 0;
   if (vkEnumerateDeviceExtensionProperties(physical, NULL, &extension_count, NULL) ==
       VK_SUCCESS && extension_count > 0) {
      VkExtensionProperties *extensions = calloc(extension_count, sizeof(*extensions));
      if (extensions &&
          vkEnumerateDeviceExtensionProperties(physical, NULL, &extension_count,
                                               extensions) == VK_SUCCESS) {
         for (uint32_t i = 0; i < extension_count; i++) {
            if (options.print_all)
               printf("DEVICE_EXTENSION=%s (rev %u)\n", extensions[i].extensionName,
                      extensions[i].specVersion);
            if (!strcmp(extensions[i].extensionName,
                        "VK_EXT_map_memory_placed")) extension_present = true;
            if (!strcmp(extensions[i].extensionName,
                        "VK_KHR_map_memory2")) map_memory2_present = true;
         }
      }
      free(extensions);
   }
   printf("EXTENSION_NAME=VK_EXT_map_memory_placed\n");
   printf("EXTENSION_PRESENT=%d\n", extension_present ? 1 : 0);
   printf("MAP_MEMORY2_EXTENSION_PRESENT=%d\n", map_memory2_present ? 1 : 0);

   VkPhysicalDeviceMapMemoryPlacedFeaturesEXT placed_features = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT,
   };
   VkPhysicalDeviceMapMemoryPlacedPropertiesEXT placed_properties = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT,
   };
   VkPhysicalDeviceFeatures2 features = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
      .pNext = &placed_features,
   };
   VkPhysicalDeviceProperties2 properties2 = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
      .pNext = &placed_properties,
   };
   vkGetPhysicalDeviceFeatures2(physical, &features);
   vkGetPhysicalDeviceProperties2(physical, &properties2);
   printf("MEMORY_MAP_PLACED=%d\n", placed_features.memoryMapPlaced ? 1 : 0);
   printf("MEMORY_MAP_RANGE_PLACED=%d\n", placed_features.memoryMapRangePlaced ? 1 : 0);
   printf("MEMORY_UNMAP_RESERVE=%d\n", placed_features.memoryUnmapReserve ? 1 : 0);
   printf("MIN_PLACED_MAP_ALIGNMENT=%llu\n",
          (unsigned long long)placed_properties.minPlacedMemoryMapAlignment);

   bool supported = extension_present && placed_features.memoryMapPlaced &&
                    placed_features.memoryMapRangePlaced;
   if (!supported) {
      if (options.require_extension) {
         if (options.validation) destroy_messenger(instance, messenger, NULL);
         vkDestroyInstance(instance, NULL);
         return finish("FAIL", "VK_EXT_map_memory_placed not fully supported", 1);
      }
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      if (options.expect_skip)
         return finish("SKIP", "extension or placed-map feature bits absent", 77);
      return finish("FAIL", "extension or placed-map feature bits absent", 1);
   }
   if (options.expect_skip) {
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "extension unexpectedly supported but skip was expected", 1);
   }

   /* Supported mode: create a device with the extension + features, allocate
    * host-visible memory and exercise placed and range-placed mapping through
    * vkMapMemory2. */
   float priority = 1.0f;
   uint32_t family_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, NULL);
   if (family_count == 0) {
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "no queue families", 1);
   }
   VkQueueFamilyProperties *families = calloc(family_count, sizeof(*families));
   if (!families) return 1;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, families);
   uint32_t queue_family = 0;
   for (uint32_t i = 0; i < family_count; i++) {
      if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { queue_family = i; break; }
   }
   free(families);
   VkDeviceQueueCreateInfo queue_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = queue_family,
      .queueCount = 1,
      .pQueuePriorities = &priority,
   };
   VkPhysicalDeviceMapMemoryPlacedFeaturesEXT enabled_placed_features = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT,
      .memoryMapPlaced = VK_TRUE,
      .memoryMapRangePlaced = VK_TRUE,
   };
   VkPhysicalDeviceFeatures2 enabled_features = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
      .pNext = &enabled_placed_features,
   };
   /* VK_EXT_map_memory_placed depends on VK_KHR_map_memory2 (or Vulkan 1.4);
    * enable the dependency extension too when the device advertises it. */
   const char *device_extensions[2] = {"VK_EXT_map_memory_placed", NULL};
   uint32_t device_extension_count = 1;
   if (map_memory2_present) {
      device_extensions[device_extension_count++] = "VK_KHR_map_memory2";
   }
   VkDeviceCreateInfo device_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .pNext = &enabled_features,
      .queueCreateInfoCount = 1,
      .pQueueCreateInfos = &queue_info,
      .enabledExtensionCount = device_extension_count,
      .ppEnabledExtensionNames = device_extensions,
   };
   VkDevice device = VK_NULL_HANDLE;
   result = vkCreateDevice(physical, &device_info, NULL, &device);
   printf("DEVICE_CREATED=%d\n", result == VK_SUCCESS ? 1 : 0);
   if (result != VK_SUCCESS) {
      fprintf(stderr, "vkCreateDevice failed: %s (%d)\n", result_string(result),
              (int)result);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "vkCreateDevice with placed features failed", 1);
   }

   PFN_vkMapMemory2 map_memory2 = (PFN_vkMapMemory2)
      vkGetDeviceProcAddr(device, "vkMapMemory2");
   if (!map_memory2)
      map_memory2 = (PFN_vkMapMemory2)vkGetDeviceProcAddr(device, "vkMapMemory2KHR");
   PFN_vkUnmapMemory2 unmap_memory2 = (PFN_vkUnmapMemory2)
      vkGetDeviceProcAddr(device, "vkUnmapMemory2");
   if (!unmap_memory2)
      unmap_memory2 = (PFN_vkUnmapMemory2)vkGetDeviceProcAddr(device, "vkUnmapMemory2KHR");
   if (!map_memory2 || !unmap_memory2) {
      vkDestroyDevice(device, NULL);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "vkMapMemory2/vkUnmapMemory2 entry points missing", 1);
   }

   VkBufferCreateInfo buffer_info = {
      .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
      .size = 4096,
      .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
   };
   VkBuffer buffer = VK_NULL_HANDLE;
   result = vkCreateBuffer(device, &buffer_info, NULL, &buffer);
   if (result != VK_SUCCESS) {
      vkDestroyDevice(device, NULL);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "vkCreateBuffer failed", 1);
   }
   VkMemoryRequirements requirements;
   vkGetBufferMemoryRequirements(device, buffer, &requirements);
   VkPhysicalDeviceMemoryProperties memory_properties;
   vkGetPhysicalDeviceMemoryProperties(physical, &memory_properties);
   int32_t memory_type = -1;
   for (uint32_t i = 0; i < memory_properties.memoryTypeCount; i++) {
      VkMemoryPropertyFlags flags = memory_properties.memoryTypes[i].propertyFlags;
      if (!(requirements.memoryTypeBits & (1u << i))) continue;
      if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
          (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
         memory_type = (int32_t)i;
         break;
      }
      if (memory_type < 0 && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
         memory_type = (int32_t)i;
   }
   if (memory_type < 0) {
      vkDestroyBuffer(device, buffer, NULL);
      vkDestroyDevice(device, NULL);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "no host-visible memory type for the buffer", 1);
   }
   VkMemoryAllocateInfo allocate_info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size,
      .memoryTypeIndex = (uint32_t)memory_type,
   };
   VkDeviceMemory memory = VK_NULL_HANDLE;
   result = vkAllocateMemory(device, &allocate_info, NULL, &memory);
   if (result != VK_SUCCESS) {
      vkDestroyBuffer(device, buffer, NULL);
      vkDestroyDevice(device, NULL);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "vkAllocateMemory failed", 1);
   }
   vkBindBufferMemory(device, buffer, memory, 0);

   void *placed_address = NULL;
   size_t placement_size = 64 * 1024;
   if (posix_memalign(&placed_address, 64 * 1024, placement_size) != 0) {
      placed_address = NULL;
   }
   if (!placed_address) {
      vkFreeMemory(device, memory, NULL);
      vkDestroyBuffer(device, buffer, NULL);
      vkDestroyDevice(device, NULL);
      if (options.validation) destroy_messenger(instance, messenger, NULL);
      vkDestroyInstance(instance, NULL);
      return finish("FAIL", "could not reserve a page-aligned placement address", 1);
   }

   VkMemoryMapPlacedInfoEXT placed_info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT,
      .pPlacedAddress = placed_address,
   };
   VkMemoryMapInfo placed_map_info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO,
      .flags = VK_MEMORY_MAP_PLACED_BIT_EXT,
      .memory = memory,
      .offset = 0,
      .size = VK_WHOLE_SIZE,
      .pNext = &placed_info,
   };
   void *mapped = NULL;
   VkResult placed_result = map_memory2(device, &placed_map_info, &mapped);
   printf("PLACED_MAP_RESULT=%d (%s)\n", (int)placed_result,
          result_string(placed_result));
   if (placed_result == VK_SUCCESS) {
      VkMemoryUnmapInfo unmap_info = {
         .sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO,
         .memory = memory,
      };
      VkResult unmap_result = unmap_memory2(device, &unmap_info);
      printf("UNMAP2_RESULT=%d (%s)\n", (int)unmap_result, result_string(unmap_result));
      if (unmap_result != VK_SUCCESS) placed_result = unmap_result;
   }

   VkResult range_result = VK_SUCCESS;
   VkMemoryMapPlacedInfoEXT range_placed_info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT,
      .pPlacedAddress = placed_address,
   };
   VkMemoryMapInfo range_map_info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO,
      .flags = VK_MEMORY_MAP_PLACED_BIT_EXT,
      .memory = memory,
      .offset = 0,
      .size = 4096,
      .pNext = &range_placed_info,
   };
   void *range_mapped = NULL;
   range_result = map_memory2(device, &range_map_info, &range_mapped);
   printf("PLACED_RANGE_MAP_RESULT=%d (%s)\n", (int)range_result,
          result_string(range_result));
   if (range_result == VK_SUCCESS) {
      VkMemoryUnmapInfo unmap_info = {
         .sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO,
         .memory = memory,
      };
      VkResult unmap_result = unmap_memory2(device, &unmap_info);
      if (unmap_result != VK_SUCCESS) {
         printf("RANGE_UNMAP2_RESULT=%d (%s)\n", (int)unmap_result,
                result_string(unmap_result));
         range_result = unmap_result;
      }
   }

   vkFreeMemory(device, memory, NULL);
   VkResult free_result = VK_SUCCESS;
   vkDestroyBuffer(device, buffer, NULL);
   vkDestroyDevice(device, NULL);
   free(placed_address);
   if (options.validation) destroy_messenger(instance, messenger, NULL);
   vkDestroyInstance(instance, NULL);
   printf("FREE_MEMORY_RESULT=%d (%s)\n", (int)free_result,
          result_string(free_result));

   if (placed_result != VK_SUCCESS)
      return finish("FAIL", "placed vkMapMemory2 did not return VK_SUCCESS", 1);
   if (range_result != VK_SUCCESS)
      return finish("FAIL", "range-placed vkMapMemory2 did not return VK_SUCCESS", 1);
   if (free_result != VK_SUCCESS)
      return finish("FAIL", "vkFreeMemory failed", 1);
   return finish("PASS", "placed and range-placed mapping succeeded", 0);
}
