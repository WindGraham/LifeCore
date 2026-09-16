import { useLocation } from "react-router";

/**
 * LifeCore 嵌入页：iframe 加载 lifecore 控制台（/lc）的指定视图（console 原生 UI）。
 * 路由段 → console hash：lc-chat→sessions、lc-notify→notify、lc-channels→channels、
 * lc-jobs→jobs、lc-settings→settings。?embed=1 使 console 去壳全宽渲染。
 */
export default function LifeCorePage() {
  const seg = useLocation().pathname.split("/").filter(Boolean).pop() || "lc-chat";
  const view = seg.replace(/^lc-/, "") || "chat";
  return (
    <iframe
      title="LifeCore"
      src={`/lc?embed=1#${view}`}
      style={{ width: "100%", height: "calc(100vh - 48px)", border: 0, display: "block" }}
    />
  );
}
