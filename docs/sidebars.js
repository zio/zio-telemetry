const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Telemetry",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "opentracing",
        "opentracing-example",
        "opencensus",
        "opentelemetry",
        "record-metrics-with-opentelemetry",
        "migrate-to-zio-telemetry-4",
        "opentelemetry-zio-logging",
        "opentelemetry-example",
        "opentelemetry-instrumentation-example"
      ]
    }
  ]
};

module.exports = sidebars;
