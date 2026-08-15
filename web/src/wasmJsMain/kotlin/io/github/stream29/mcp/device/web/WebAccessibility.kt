@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.mcp.device.web

/**
 * Repairs two ARIA gaps in the Compose Multiplatform 1.12 web semantics bridge.
 *
 * The bridge emits headings without the required level and applies an explicit
 * `generic` role to the backing canvas. The semantic overlay remains the source
 * of all accessible content.
 */
internal fun installComposeWebAccessibilityCompatibility() {
    js(
        """
        (() => {
          const app = document.getElementById("app");
          if (!app || app.dataset.composeA11yRepair === "true") return;
          app.dataset.composeA11yRepair = "true";

          const observedRoots = new WeakSet();
          const repair = (root) => {
            const headings = Array.from(root.querySelectorAll('[role="heading"]'));
            headings.forEach((heading, index) => {
              heading.setAttribute("aria-level", index === 0 ? "1" : "2");
            });
            root.querySelectorAll('canvas[role="generic"]').forEach((canvas) => {
              canvas.removeAttribute("role");
            });
          };

          const shadowObserver = new MutationObserver((mutations) => {
            const roots = new Set();
            mutations.forEach((mutation) => {
              const root = mutation.target.getRootNode();
              if (root instanceof ShadowRoot) roots.add(root);
            });
            roots.forEach(repair);
          });

          const discover = () => {
            app.querySelectorAll("*").forEach((element) => {
              const root = element.shadowRoot;
              if (!root || observedRoots.has(root)) return;
              observedRoots.add(root);
              shadowObserver.observe(root, {
                subtree: true,
                childList: true,
                attributes: true,
                attributeFilter: ["role"]
              });
              repair(root);
            });
          };

          new MutationObserver(discover).observe(app, {
            subtree: true,
            childList: true
          });
          discover();
          requestAnimationFrame(discover);
        })()
        """,
    )
}
