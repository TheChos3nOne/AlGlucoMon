import React from "react";
import ReactDOM from "react-dom/client";
import { Overlay } from "./components/Overlay";
import { Settings } from "./components/Settings";
import "./styles.css";

const isSettings = window.location.hash === "#settings";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {isSettings ? <Settings /> : <Overlay />}
  </React.StrictMode>
);
