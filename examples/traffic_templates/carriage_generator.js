// MineScreen import-time generator example. It is converted to declarative JSON once and is not
// executed during rendering, ticking, or on a server.
function generate() {
  return {
    id: "carriage_next_stop",
    name: "Carriage next-stop display",
    layout: "script_scene_v1",
    background_color: "#FF02070A",
    text_color: "#FFFFFFFF",
    accent_color: "#FF36E6FF",
    elements: [
      {type: "rect", x: 0.02, y: 0.08, width: 0.96, height: 0.84, color: "#FF061820"},
      {type: "text", x: 0.50, y: 0.18, align: "center", size: 1.5,
       color: "#FF36E6FF", text: "${line}  →  ${destination}"},
      {type: "line", x: 0.10, y: 0.55, x2: 0.90, y2: 0.55,
       size: 2.0, color: "#FF36E6FF"},
      {type: "text", x: 0.50, y: 0.66, align: "center", size: 1.25,
       color: "#FFFFFFFF", text: "Next: ${next}  ${eta}"}
    ],
    traffic: {line: "A1", destination: "Central", next: "Museum", eta: "2 min"}
  };
}
