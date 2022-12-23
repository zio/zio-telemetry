const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Telemetry",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "index",
        "opentracing",
        "opentracing-example",
        "opencensus",
        "opentelemetry",
        "opentelemetry-example",
      ]
    }
  ]
};

module.exports = sidebars;
