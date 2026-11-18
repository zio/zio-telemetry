const sidebars = {
  sidebar: [
    "index",
    "getting-started",
    {
      type: "category",
      label: "Concepts",
      collapsed: false,
      link: { type: "doc", id: "concepts/index" },
      items: [
        "concepts/signals-and-spans",
        "concepts/context-and-fibers",
        "concepts/context-propagation",
        "concepts/choosing-a-module"
      ]
    },
    {
      type: "category",
      label: "Reference",
      collapsed: false,
      items: [
        {
          type: "category",
          label: "OpenTelemetry",
          collapsed: false,
          link: { type: "doc", id: "opentelemetry" },
          items: [
            "reference/opentelemetry-layers",
            "reference/tracing",
            "reference/status-mapper",
            "reference/attributes",
            "reference/metrics",
            "reference/logging",
            "reference/baggage",
            "reference/context-storage",
            "reference/context-propagation"
          ]
        },
        {
          type: "category",
          label: "Legacy Modules",
          collapsed: false,
          items: ["opentracing", "opencensus"]
        }
      ]
    },
    "opentelemetry-zio-logging",
    {
      type: "category",
      label: "Tutorials",
      collapsed: true,
      items: [
        "tutorials/instrument-an-order-service",
        "opentelemetry-example",
        "opentelemetry-instrumentation-example",
        "opentracing-example"
      ]
    },
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "how-to/export-signals-with-otlp",
        "how-to/use-the-java-agent",
        "how-to/propagate-context-over-http",
        "how-to/record-metrics",
        "how-to/customize-span-status",
        "how-to/test-traced-code",
        "how-to/correlate-logs-with-traces",
        "how-to/wrap-blocking-code-in-a-span",
        "how-to/migrate-from-opentracing-or-opencensus"
      ]
    },
    "whats-new",
    "resources"
  ]
};

module.exports = sidebars;
