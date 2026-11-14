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

import { SERVICE_COMPONENT, isServiceEnabled, readPageSnapshot } from "../service";

/** Reports whether the built-in accessibility service is switched on. */
export function check_accessibility_service() {
  isServiceEnabled()
    .then((enabled) => {
      complete({
        success: true,
        enabled,
        component: SERVICE_COMPONENT,
        message: enabled
          ? "界面自动化服务已开启。"
          : "界面自动化服务尚未开启，请在系统无障碍设置中启用「Operit 界面自动化」。",
      });
    })
    .catch((error: unknown) => {
      complete({
        success: false,
        error: error instanceof Error ? error.message : String(error),
      });
    });
}

/** Reads one live snapshot to verify the automation path answers. */
export function probe_ui_snapshot() {
  readPageSnapshot()
    .then((page) => {
      complete({
        success: true,
        package_name: page.packageName,
        activity_name: page.activityName,
        node_count: page.nodeCount,
        truncated: page.truncated,
        first_texts: page.firstTexts,
        message: `读取成功：${page.packageName} / ${page.activityName}，节点 ${page.nodeCount} 个`,
      });
    })
    .catch((error: unknown) => {
      complete({
        success: false,
        error: error instanceof Error ? error.message : String(error),
      });
    });
}
