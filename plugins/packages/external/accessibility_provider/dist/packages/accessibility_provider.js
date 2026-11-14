/* METADATA
{
    "name": "accessibility_provider",
    "display_name": {
        "zh": "界面自动化服务",
        "en": "UI Automation Provider"
    },
    "description": {
        "zh": "检查并验证内置无障碍服务，读取当前界面快照。",
        "en": "Checks the built-in accessibility service and reads one screen snapshot."
    },
    "enabledByDefault": true,
    "tools": [
        {
            "name": "check_accessibility_service",
            "description": {
                "zh": "检查内置于主应用的无障碍服务是否已在系统设置中开启。",
                "en": "Reports whether the accessibility service bundled with the app is switched on."
            },
            "parameters": []
        },
        {
            "name": "probe_ui_snapshot",
            "description": {
                "zh": "读取当前界面快照，验证界面自动化链路是否可用。",
                "en": "Reads the current screen snapshot to verify the automation path."
            },
            "parameters": []
        }
    ]
}
*/
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var __async = (__this, __arguments, generator) => {
  return new Promise((resolve, reject) => {
    var fulfilled = (value) => {
      try {
        step(generator.next(value));
      } catch (e) {
        reject(e);
      }
    };
    var rejected = (value) => {
      try {
        step(generator.throw(value));
      } catch (e) {
        reject(e);
      }
    };
    var step = (x) => x.done ? resolve(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
    step((generator = generator.apply(__this, __arguments)).next());
  });
};

// src/packages/accessibility_provider.ts
var accessibility_provider_exports = {};
__export(accessibility_provider_exports, {
  check_accessibility_service: () => check_accessibility_service,
  probe_ui_snapshot: () => probe_ui_snapshot
});
module.exports = __toCommonJS(accessibility_provider_exports);

// src/service.ts
var SERVICE_COMPONENT = "app.operit/app.operit.OperitAccessibilityService";
function isServiceEnabled() {
  return __async(this, null, function* () {
    const data = yield Tools.System.getSetting("enabled_accessibility_services", "secure");
    return String(data.value ?? "").split(":").some((entry) => entry.trim().toLowerCase() === SERVICE_COMPONENT);
  });
}
function readPageSnapshot() {
  return __async(this, null, function* () {
    const page = yield Tools.UI.getPageInfo();
    const texts = [];
    const walk = (node) => {
      const label = (node.text ?? node.contentDesc ?? "").toString().trim();
      if (label && texts.length < 8) texts.push(label);
      const children = node.children ?? [];
      for (const child of children) if (texts.length < 8) walk(child);
    };
    let count = 0;
    const countNodes = (node) => {
      count += 1;
      const children = node.children ?? [];
      for (const child of children) countNodes(child);
    };
    const root = page.uiElements;
    countNodes(root);
    walk(root);
    return {
      packageName: page.packageName ?? "",
      activityName: page.activityName ?? "",
      nodeCount: count,
      truncated: Boolean(page.truncated),
      firstTexts: texts
    };
  });
}

// src/packages/accessibility_provider.ts
function check_accessibility_service() {
  isServiceEnabled().then((enabled) => {
    complete({
      success: true,
      enabled,
      component: SERVICE_COMPONENT,
      message: enabled ? "界面自动化服务已开启。" : "界面自动化服务尚未开启，请在系统无障碍设置中启用「Operit 界面自动化」。"
    });
  }).catch((error) => {
    complete({
      success: false,
      error: error instanceof Error ? error.message : String(error)
    });
  });
}
function probe_ui_snapshot() {
  readPageSnapshot().then((page) => {
    complete({
      success: true,
      package_name: page.packageName,
      activity_name: page.activityName,
      node_count: page.nodeCount,
      truncated: page.truncated,
      first_texts: page.firstTexts,
      message: `读取成功：${page.packageName} / ${page.activityName}，节点 ${page.nodeCount} 个`
    });
  }).catch((error) => {
    complete({
      success: false,
      error: error instanceof Error ? error.message : String(error)
    });
  });
}
