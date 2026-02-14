(function bootstrapCharts(global) {
    "use strict";

    var instances = {};
    var DEFAULT_AXIS_COLOR = "#1f2f21";
    var DEFAULT_GRID_COLOR = "rgba(31, 47, 33, 0.14)";
    var DEFAULT_TEXT_COLOR = "#0f1b11";

    function cssVar(name, fallback) {
        try {
            var value = global.getComputedStyle(document.documentElement).getPropertyValue(name);
            return value && value.trim() ? value.trim() : fallback;
        } catch (_unused) {
            return fallback;
        }
    }

    function resolveCanvas(target) {
        if (!target) {
            return null;
        }
        if (typeof target === "string") {
            return document.getElementById(target);
        }
        return target;
    }

    function destroyChart(target) {
        var canvas = resolveCanvas(target);
        if (!canvas || !canvas.id) {
            return;
        }
        var existing = instances[canvas.id];
        if (existing && typeof existing.destroy === "function") {
            existing.destroy();
        }
        delete instances[canvas.id];
    }

    function destroyAll() {
        Object.keys(instances).forEach(function eachChart(id) {
            if (instances[id] && typeof instances[id].destroy === "function") {
                instances[id].destroy();
            }
        });
        instances = {};
    }

    function renderFallback(canvas, message) {
        var target = resolveCanvas(canvas);
        if (!target) {
            return;
        }
        var parent = target.parentElement;
        if (!parent) {
            return;
        }
        parent.innerHTML = "<div class=\"empty-state\"><strong>Chart unavailable</strong><p>" + message + "</p></div>";
    }

    function render(target, config) {
        var canvas = resolveCanvas(target);
        if (!canvas) {
            return null;
        }
        destroyChart(canvas);

        if (typeof global.Chart === "undefined") {
            renderFallback(canvas, "Chart.js did not load.");
            return null;
        }

        var chart = null;
        global.requestAnimationFrame(function frame() {
            chart = new global.Chart(canvas.getContext("2d"), config);
            if (canvas.id) {
                instances[canvas.id] = chart;
            }
        });
        return chart;
    }

    function line(target, spec) {
        spec = spec || {};
        var axisColor = cssVar("--text-muted", DEFAULT_AXIS_COLOR);
        var gridColor = DEFAULT_GRID_COLOR;
        var textColor = cssVar("--text", DEFAULT_TEXT_COLOR);
        return render(target, {
            type: "line",
            data: {
                labels: spec.labels || [],
                datasets: [
                    {
                        label: spec.label || "",
                        data: spec.values || [],
                        borderColor: spec.color || "#2b9d67",
                        backgroundColor: spec.fill ? "rgba(43,157,103,0.16)" : "transparent",
                        fill: Boolean(spec.fill),
                        tension: 0.32,
                        pointRadius: 2,
                        pointHoverRadius: 4
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: {
                    duration: spec.animate === false ? 0 : 260
                },
                scales: {
                    x: {
                        ticks: {
                            color: axisColor
                        },
                        grid: {
                            color: gridColor
                        }
                    },
                    y: {
                        beginAtZero: spec.beginAtZero !== false,
                        ticks: {
                            color: axisColor,
                            callback: spec.tickFormatter || undefined
                        },
                        grid: {
                            color: gridColor
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: Boolean(spec.showLegend),
                        labels: {
                            color: textColor
                        }
                    },
                    tooltip: {
                        callbacks: spec.tooltipCallbacks || undefined
                    }
                }
            }
        });
    }

    function bar(target, spec) {
        spec = spec || {};
        var axisColor = cssVar("--text-muted", DEFAULT_AXIS_COLOR);
        var gridColor = DEFAULT_GRID_COLOR;
        return render(target, {
            type: "bar",
            data: {
                labels: spec.labels || [],
                datasets: [
                    {
                        label: spec.label || "",
                        data: spec.values || [],
                        borderRadius: 7,
                        maxBarThickness: 36,
                        backgroundColor: spec.color || "#4f8f6a"
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: {
                    duration: spec.animate === false ? 0 : 240
                },
                scales: {
                    x: {
                        ticks: {
                            color: axisColor
                        },
                        grid: {
                            color: gridColor
                        }
                    },
                    y: {
                        beginAtZero: true,
                        ticks: {
                            color: axisColor,
                            callback: spec.tickFormatter || undefined
                        },
                        grid: {
                            color: gridColor
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: spec.tooltipCallbacks || undefined
                    }
                }
            }
        });
    }

    function horizontalDuration(target, spec) {
        spec = spec || {};
        var axisColor = cssVar("--text-muted", DEFAULT_AXIS_COLOR);
        var gridColor = DEFAULT_GRID_COLOR;
        return render(target, {
            type: "bar",
            data: {
                labels: spec.labels || [],
                datasets: [
                    {
                        data: spec.values || [],
                        borderRadius: 8,
                        backgroundColor: spec.color || "#2f9d8d"
                    }
                ]
            },
            options: {
                indexAxis: "y",
                responsive: true,
                maintainAspectRatio: false,
                animation: {
                    duration: spec.animate === false ? 0 : 220
                },
                plugins: {
                    legend: { display: false }
                },
                scales: {
                    x: {
                        beginAtZero: true,
                        ticks: {
                            color: axisColor,
                            callback: spec.tickFormatter || undefined
                        },
                        grid: {
                            color: gridColor
                        }
                    },
                    y: {
                        ticks: {
                            color: axisColor
                        },
                        grid: {
                            color: gridColor
                        }
                    }
                }
            }
        });
    }

    global.HelioCharts = {
        line: line,
        bar: bar,
        horizontalDuration: horizontalDuration,
        destroy: destroyChart,
        destroyAll: destroyAll
    };
})(window);
