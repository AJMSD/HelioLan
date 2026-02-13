(function bootstrapCharts(global) {
    "use strict";

    var instances = {};

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
                    y: {
                        beginAtZero: spec.beginAtZero !== false,
                        ticks: {
                            callback: spec.tickFormatter || undefined
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: Boolean(spec.showLegend)
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
                    y: {
                        beginAtZero: true,
                        ticks: {
                            callback: spec.tickFormatter || undefined
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
                            callback: spec.tickFormatter || undefined
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
