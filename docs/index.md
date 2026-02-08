---
hide:
  - toc
---

<style>
/* Keep sidebar but hide TOC on index page */
.md-sidebar--secondary,
.md-sidebar--primary {
  display: none;
}

.md-content__inner {
  max-width: 800px !important;
  padding: 0 1rem !important;
  margin: 0 auto !important;
}

.intro-header {
  text-align: center;
  padding: 2rem 0 1rem 0;
}

.intro-header img {
  height: 64px;
  margin-bottom: 1rem;
  transition: transform 0.8s ease, filter 0.8s ease;
}

.intro-header img:hover {
  transform: scale(1.06);
  filter: drop-shadow(0 6px 20px rgba(0, 0, 0, 0.12));
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

/* Magnetic snap animation */
.magnet-demo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0;
  margin-bottom: 1.5rem;
  height: 24px;
}

.magnet-demo .node {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  opacity: 0.7;
}

.magnet-demo .node-left {
  animation: magnet-left 3s ease-in-out infinite;
}

.magnet-demo .node-right {
  animation: magnet-right 3s ease-in-out infinite;
}

.magnet-demo .connector {
  font-size: 0.75rem;
  opacity: 0;
  font-family: var(--mono, monospace);
  color: var(--md-primary-fg-color);
  animation: connector-appear 3s ease-in-out infinite;
  margin: 0 2px;
}

@keyframes magnet-left {
  0%, 10% { transform: translateX(-12px); opacity: 0.5; }
  40%, 60% { transform: translateX(0); opacity: 0.9; }
  90%, 100% { transform: translateX(-12px); opacity: 0.5; }
}

@keyframes magnet-right {
  0%, 10% { transform: translateX(12px); opacity: 0.5; }
  40%, 60% { transform: translateX(0); opacity: 0.9; }
  90%, 100% { transform: translateX(12px); opacity: 0.5; }
}

@keyframes connector-appear {
  0%, 30% { opacity: 0; }
  45%, 55% { opacity: 0.6; }
  70%, 100% { opacity: 0; }
}

/* Environment merge animation */
.env-merge-demo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin: 2rem 0;
  font-family: var(--mono, monospace);
  font-size: 0.65rem;
}

.env-merge-demo .env {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.4rem;
}

.env-merge-demo .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
}

.env-merge-demo .env-label {
  font-size: 0.6rem;
  opacity: 0.6;
  white-space: nowrap;
}

.env-merge-demo .env-a {
  animation: env-drift-left 5s ease-in-out infinite;
}

.env-merge-demo .env-b {
  animation: env-drift-right 5s ease-in-out infinite;
}

.env-merge-demo .env-result {
  animation: env-result-appear 5s ease-in-out infinite;
}

.env-merge-demo .env-result .dot {
  box-shadow: 0 0 8px var(--md-primary-fg-color);
}

.env-merge-demo .env-result .env-label {
  opacity: 0.8;
}

.env-merge-demo .merge-op {
  opacity: 0.4;
  animation: op-pulse 5s ease-in-out infinite;
}

.env-merge-demo .merge-eq {
  opacity: 0;
  animation: eq-appear 5s ease-in-out infinite;
}

@keyframes env-drift-left {
  0%, 15% { transform: translateX(-12px); opacity: 0.3; }
  40%, 60% { transform: translateX(0); opacity: 1; }
  85%, 100% { transform: translateX(-12px); opacity: 0.3; }
}

@keyframes env-drift-right {
  0%, 15% { transform: translateX(12px); opacity: 0.3; }
  40%, 60% { transform: translateX(0); opacity: 1; }
  85%, 100% { transform: translateX(12px); opacity: 0.3; }
}

@keyframes env-result-appear {
  0%, 45% { opacity: 0; transform: scale(0.7); }
  55%, 75% { opacity: 1; transform: scale(1); }
  90%, 100% { opacity: 0; transform: scale(0.7); }
}

@keyframes op-pulse {
  0%, 20% { opacity: 0.2; }
  40%, 60% { opacity: 0.6; }
  80%, 100% { opacity: 0.2; }
}

@keyframes eq-appear {
  0%, 45% { opacity: 0; }
  55%, 75% { opacity: 0.6; }
  90%, 100% { opacity: 0; }
}

/* Branch/union animation */
.branch-demo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  font-family: var(--mono, monospace);
  font-size: 0.65rem;
}

.branch-demo .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
}

.branch-demo .branch-input {
  animation: branch-input-pulse 5s ease-in-out infinite;
}

.branch-demo .branch-split {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.branch-demo .branch {
  display: flex;
  align-items: center;
  gap: 0.3rem;
}

.branch-demo .branch-label {
  font-size: 0.55rem;
  opacity: 0.5;
}

.branch-demo .env-label {
  font-size: 0.6rem;
  opacity: 0.6;
}

.branch-demo .branch-if {
  animation: branch-if-appear 5s ease-in-out infinite;
}

.branch-demo .branch-else {
  animation: branch-else-appear 5s ease-in-out infinite;
}

.branch-demo .merge-eq {
  opacity: 0;
  animation: eq-appear 5s ease-in-out infinite;
}

.branch-demo .branch-result {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.3rem;
  animation: env-result-appear 5s ease-in-out infinite;
}

.branch-demo .branch-result .dot {
  box-shadow: 0 0 8px var(--md-primary-fg-color);
}

.branch-demo .branch-result .env-label {
  opacity: 0.8;
}

@keyframes branch-input-pulse {
  0%, 15% { opacity: 0.4; transform: scale(0.9); }
  25%, 40% { opacity: 1; transform: scale(1); }
  60%, 100% { opacity: 0.4; transform: scale(0.9); }
}

@keyframes branch-if-appear {
  0%, 20% { opacity: 0; transform: translateY(4px); }
  35%, 55% { opacity: 1; transform: translateY(0); }
  75%, 100% { opacity: 0; transform: translateY(4px); }
}

@keyframes branch-else-appear {
  0%, 25% { opacity: 0; transform: translateY(-4px); }
  40%, 55% { opacity: 1; transform: translateY(0); }
  75%, 100% { opacity: 0; transform: translateY(-4px); }
}

/* Trace animation - data flowing through pipeline with trace channel */
.trace-demo {
  position: relative;
  width: 340px;
  height: 140px;
  font-family: var(--mono, monospace);
  font-size: 0.5rem;
  margin: 0 auto;
}

.trace-demo .pipeline {
  position: absolute;
  top: 0;
  left: 5px;
  display: flex;
  align-items: flex-start;
  gap: 0;
}

.trace-demo .node-group {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.trace-demo .trace-node {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  opacity: 0.75;
}

.trace-demo .node-label {
  font-size: 0.4rem;
  margin-top: 2px;
  opacity: 0.7;
}

/* Connector line from node down to trace channel */
.trace-demo .node-connector {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  width: 1px;
  height: 0;
  background: var(--md-primary-fg-color);
  opacity: 0;
}

.trace-demo .node-connector.read {
  border-left: 1px dashed var(--md-primary-fg-color);
  background: none;
  width: 0;
}

/* Labels for write/read actions */
.trace-demo .node-label-action {
  font-size: 0.38rem;
  opacity: 0;
  position: absolute;
  white-space: nowrap;
  transform: translateX(-50%);
  left: 50%;
  top: 42px;
}

.trace-demo .node-label-action.write {
  color: var(--md-primary-fg-color);
}

.trace-demo .node-label-action.read {
  color: #888;
  font-style: italic;
}

.trace-demo .trace-arrow {
  font-size: 0.5rem;
  opacity: 0.4;
  margin: 0 18px;
  color: var(--md-primary-fg-color);
  line-height: 12px;
}

.trace-demo .trace-dot {
  position: absolute;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  box-shadow: 0 0 8px var(--md-primary-fg-color);
  top: 3px;
  opacity: 0;
}

.trace-demo .trace-dot.bad {
  background: #ef4444;
  box-shadow: 0 0 8px #ef4444;
}

/* Write indicator - shows node logging to trace */
.trace-demo .write-indicator {
  position: absolute;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  opacity: 0;
}

.trace-demo .node-write {
  font-size: 0.28rem;
  opacity: 0;
  color: var(--md-primary-fg-color);
  position: absolute;
  white-space: nowrap;
}

/* The trace channel - runs below pipeline */
.trace-demo .channel {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 220px;
  height: 65px;
  border: 1px solid rgba(20, 184, 166, 0.3);
  border-radius: 4px;
  display: flex;
  overflow: hidden;
}

.trace-demo .channel-title {
  position: absolute;
  bottom: -16px;
  left: 0;
  font-size: 0.38rem;
  font-weight: 500;
  opacity: 0.4;
  color: var(--md-default-fg-color);
  letter-spacing: 0.03em;
}

.trace-demo .channel-logs {
  flex: 1;
  padding: 6px 10px;
  border-right: 1px solid rgba(20, 184, 166, 0.3);
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow: hidden;
}

.trace-demo .channel-time {
  width: 48px;
  padding: 5px 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.trace-demo .channel-label {
  font-size: 0.38rem;
  opacity: 0.5;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.trace-demo .log-entry {
  font-size: 0.42rem;
  color: var(--md-primary-fg-color);
  opacity: 0;
  white-space: nowrap;
}

.trace-demo .log-entry.error {
  color: #ef4444;
}

.trace-demo .timer-display {
  font-size: 0.55rem;
  color: var(--md-primary-fg-color);
  font-weight: 500;
  white-space: nowrap;
}

.trace-demo .timer-num {
  display: inline-block;
  position: relative;
}

.trace-demo .timer-num::before {
  content: "0";
  visibility: hidden;
}

.trace-demo .timer-val {
  position: absolute;
  top: 0;
  left: 0;
  opacity: 0;
}

.trace-demo .timer-unit {
  opacity: 0.6;
}

/* Trace output - comes out the end */
.trace-demo .trace-output {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 95px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.trace-demo .output-arrow {
  font-size: 0.7rem;
  opacity: 0.3;
  color: var(--md-primary-fg-color);
  margin-bottom: 4px;
}

.trace-demo .trace-result {
  padding: 8px 10px;
  border: 1px solid rgba(20, 184, 166, 0.35);
  border-radius: 4px;
  font-size: 0.44rem;
  opacity: 0;
  white-space: nowrap;
}

.trace-demo .trace-result-inner {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: flex-start;
}

.trace-demo .trace-result-title {
  color: var(--md-primary-fg-color);
  font-weight: 600;
  font-size: 0.48rem;
  margin-bottom: 2px;
}

.trace-demo .trace-result-line {
  opacity: 0.7;
  color: var(--md-default-fg-color);
}

.trace-demo .trace-result-line .val {
  color: var(--md-primary-fg-color);
  opacity: 1;
}

.trace-demo .trace-result-line .val.err {
  color: #ef4444;
}

/* Dark mode */
[data-md-color-scheme="slate"] .trace-demo .trace-result {
  border-color: rgba(179, 136, 255, 0.35);
}

[data-md-color-scheme="slate"] .trace-demo .trace-result-title {
  color: var(--md-primary-fg-color);
}

[data-md-color-scheme="slate"] .trace-demo .channel {
  border-color: rgba(179, 136, 255, 0.3);
}

[data-md-color-scheme="slate"] .trace-demo .channel-logs {
  border-right-color: rgba(179, 136, 255, 0.3);
}

[data-md-color-scheme="slate"] .trace-demo .trace-dot {
  box-shadow: 0 0 6px var(--md-primary-fg-color);
}

[data-md-color-scheme="slate"] .trace-demo .trace-dot.bad {
  box-shadow: 0 0 6px #ef4444;
}

@keyframes trace-dot-flow {
  0% { left: -12px; opacity: 0; }
  4% { left: -5px; opacity: 0.9; }
  10% { left: 9px; }
  28% { left: 15px; } /* slow through E */
  35% { left: 71px; }
  58% { left: 79px; } /* slow through T */
  65% { left: 135px; }
  78% { left: 143px; } /* slow through L */
  85% { left: 185px; opacity: 0.9; }
  95%, 100% { left: 210px; opacity: 0; }
}

@keyframes trace-log-1 {
  0%, 18% { opacity: 0; transform: translateX(-4px); }
  22%, 90% { opacity: 1; transform: translateX(0); }
  96%, 100% { opacity: 0; }
}

@keyframes trace-log-2 {
  0%, 52% { opacity: 0; transform: translateX(-4px); }
  56%, 90% { opacity: 1; transform: translateX(0); }
  96%, 100% { opacity: 0; }
}

@keyframes trace-log-3 {
  0%, 48% { opacity: 0; transform: translateX(-4px); }
  52%, 92% { opacity: 1; transform: translateX(0); }
  98%, 100% { opacity: 0; }
}

@keyframes trace-timer-0 {
  0%, 9.99% { opacity: 1; }
  10%, 100% { opacity: 0; }
}

@keyframes trace-timer-1 {
  0%, 10% { opacity: 0; }
  10.01%, 26% { opacity: 1; }
  26.01%, 100% { opacity: 0; }
}

@keyframes trace-timer-2 {
  0%, 26% { opacity: 0; }
  26.01%, 44% { opacity: 1; }
  44.01%, 100% { opacity: 0; }
}

@keyframes trace-timer-3 {
  0%, 44% { opacity: 0; }
  44.01%, 62% { opacity: 1; }
  62.01%, 100% { opacity: 0; }
}

@keyframes trace-timer-4 {
  0%, 62% { opacity: 0; }
  62.01%, 95% { opacity: 1; }
  100% { opacity: 0; }
}

@keyframes trace-channel-pulse {
  0%, 8% { border-color: rgba(128, 128, 128, 0.3); }
  15%, 85% { border-color: var(--md-primary-fg-color); }
  90%, 100% { border-color: rgba(128, 128, 128, 0.3); }
}

@keyframes trace-result-appear {
  0%, 78% { opacity: 0; transform: scale(0.95); }
  84%, 98% { opacity: 1; transform: scale(1); }
  100% { opacity: 0; transform: scale(0.95); }
}

@keyframes trace-output-arrow {
  0%, 76% { opacity: 0.3; }
  82%, 98% { opacity: 0.9; color: var(--md-primary-fg-color); }
  100% { opacity: 0.3; }
}

@keyframes trace-node-glow-1 {
  0%, 10% { box-shadow: none; }
  14%, 24% { box-shadow: 0 0 10px var(--md-primary-fg-color); }
  28%, 100% { box-shadow: none; }
}

@keyframes trace-node-glow-2 {
  0%, 32% { box-shadow: none; }
  36%, 54% { box-shadow: 0 0 10px var(--md-primary-fg-color); }
  58%, 100% { box-shadow: none; }
}

@keyframes trace-node-glow-3 {
  0%, 58% { box-shadow: none; }
  62%, 72% { box-shadow: 0 0 10px var(--md-primary-fg-color); }
  76%, 100% { box-shadow: none; }
}

/* Node connector reaching down to trace - for writing logs */
@keyframes trace-write-connector-1 {
  0%, 16% { opacity: 0; height: 0; }
  19%, 26% { opacity: 0.5; height: 60px; }
  30%, 100% { opacity: 0; height: 0; }
}

@keyframes trace-write-connector-2 {
  0%, 46% { opacity: 0; height: 0; }
  49%, 56% { opacity: 0.5; height: 60px; }
  60%, 100% { opacity: 0; height: 0; }
}

/* Write label appears when node logs */
@keyframes trace-write-label-1 {
  0%, 17% { opacity: 0; }
  20%, 28% { opacity: 0.8; }
  32%, 100% { opacity: 0; }
}

@keyframes trace-write-label-2 {
  0%, 47% { opacity: 0; }
  50%, 58% { opacity: 0.8; }
  62%, 100% { opacity: 0; }
}

/* Node connector for checking/reading trace */
@keyframes trace-read-connector-2 {
  0%, 34% { opacity: 0; height: 0; }
  37%, 44% { opacity: 0.4; height: 60px; }
  48%, 100% { opacity: 0; height: 0; }
}

@keyframes trace-read-connector-3 {
  0%, 64% { opacity: 0; height: 0; }
  67%, 74% { opacity: 0.4; height: 60px; }
  78%, 100% { opacity: 0; height: 0; }
}

@keyframes trace-read-label-2 {
  0%, 35% { opacity: 0; }
  38%, 46% { opacity: 0.7; }
  50%, 100% { opacity: 0; }
}

@keyframes trace-read-label-3 {
  0%, 65% { opacity: 0; }
  68%, 76% { opacity: 0.7; }
  80%, 100% { opacity: 0; }
}

/* Bad dot animation - same path but red, slightly offset timing */
@keyframes trace-dot-flow-bad {
  0% { left: -12px; opacity: 0; }
  6% { left: -5px; opacity: 0.9; }
  12% { left: 9px; }
  30% { left: 15px; } /* slow through E */
  37% { left: 71px; }
  60% { left: 79px; } /* slow through T */
  67% { left: 135px; }
  80% { left: 143px; } /* slow through L */
  87% { left: 185px; opacity: 0.9; }
  97%, 100% { left: 210px; opacity: 0; }
}

.trace-demo .trace-dot-1 { animation: trace-dot-flow 10s linear infinite; }
.trace-demo .trace-dot-2 { animation: trace-dot-flow 10s linear infinite 0.4s; }
.trace-demo .trace-dot-3 { animation: trace-dot-flow-bad 10s linear infinite; }

.trace-demo .trace-node-1 { animation: trace-node-glow-1 10s ease-out infinite; }
.trace-demo .trace-node-2 { animation: trace-node-glow-2 10s ease-out infinite; }
.trace-demo .trace-node-3 { animation: trace-node-glow-3 10s ease-out infinite; }

/* Write connectors - solid line when logging */
.trace-demo .write-conn-1 { animation: trace-write-connector-1 10s ease-out infinite; }
.trace-demo .write-conn-2 { animation: trace-write-connector-2 10s ease-out infinite; }

.trace-demo .write-label-1 { animation: trace-write-label-1 10s ease-out infinite; }
.trace-demo .write-label-2 { animation: trace-write-label-2 10s ease-out infinite; }

/* Read connectors - dashed line when checking */
.trace-demo .read-conn-2 { animation: trace-read-connector-2 10s ease-out infinite; }
.trace-demo .read-conn-3 { animation: trace-read-connector-3 10s ease-out infinite; }

.trace-demo .read-label-2 { animation: trace-read-label-2 10s ease-out infinite; }
.trace-demo .read-label-3 { animation: trace-read-label-3 10s ease-out infinite; }

.trace-demo .log-1 { animation: trace-log-1 10s ease-out infinite; }
.trace-demo .log-2 { animation: trace-log-2 10s ease-out infinite; }
.trace-demo .log-3 { animation: trace-log-3 10s ease-out infinite; }

.trace-demo .timer-0 { animation: trace-timer-0 10s ease-out infinite; }
.trace-demo .timer-1 { animation: trace-timer-1 10s ease-out infinite; }
.trace-demo .timer-2 { animation: trace-timer-2 10s ease-out infinite; }
.trace-demo .timer-3 { animation: trace-timer-3 10s ease-out infinite; }
.trace-demo .timer-4 { animation: trace-timer-4 10s ease-out infinite; }

.trace-demo .channel { animation: trace-channel-pulse 10s ease-out infinite; }
.trace-demo .trace-result { animation: trace-result-appear 10s ease-out infinite; }
.trace-demo .output-arrow { animation: trace-output-arrow 10s ease-out infinite; }

/* Platform animation */
.platform-demo {
  position: relative;
  width: 280px;
  height: 140px;
  font-family: var(--mono, monospace);
  margin: 0 auto;
}

.platform-demo .logo-source {
  width: 48px;
  height: 48px;
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  opacity: 0.85;
}

.platform-demo .drop {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  position: absolute;
  opacity: 0;
  box-shadow: 0 0 6px var(--md-primary-fg-color);
}

/* JVM - left target */
.platform-demo .drop-jvm-1 { animation: drop-to-jvm 1.6s ease-out infinite; }
.platform-demo .drop-jvm-2 { animation: drop-to-jvm 1.6s ease-out infinite 0.4s; }
.platform-demo .drop-jvm-3 { animation: drop-to-jvm 1.6s ease-out infinite 0.8s; }
.platform-demo .drop-jvm-4 { animation: drop-to-jvm 1.6s ease-out infinite 1.2s; }

/* JS - center target */
.platform-demo .drop-js-1 { animation: drop-to-js 1.6s ease-out infinite 0.13s; }
.platform-demo .drop-js-2 { animation: drop-to-js 1.6s ease-out infinite 0.53s; }
.platform-demo .drop-js-3 { animation: drop-to-js 1.6s ease-out infinite 0.93s; }
.platform-demo .drop-js-4 { animation: drop-to-js 1.6s ease-out infinite 1.33s; }

/* WASM - center-right target */
.platform-demo .drop-wasm-1 { animation: drop-to-wasm 1.6s ease-out infinite 0.2s; }
.platform-demo .drop-wasm-2 { animation: drop-to-wasm 1.6s ease-out infinite 0.6s; }
.platform-demo .drop-wasm-3 { animation: drop-to-wasm 1.6s ease-out infinite 1.0s; }
.platform-demo .drop-wasm-4 { animation: drop-to-wasm 1.6s ease-out infinite 1.4s; }

/* LLVM - right target */
.platform-demo .drop-llvm-1 { animation: drop-to-llvm 1.6s ease-out infinite 0.27s; }
.platform-demo .drop-llvm-2 { animation: drop-to-llvm 1.6s ease-out infinite 0.67s; }
.platform-demo .drop-llvm-3 { animation: drop-to-llvm 1.6s ease-out infinite 1.07s; }
.platform-demo .drop-llvm-4 { animation: drop-to-llvm 1.6s ease-out infinite 1.47s; }

.platform-demo .platform {
  position: absolute;
  bottom: 0;
  opacity: 0.85;
  display: flex;
  align-items: center;
  justify-content: center;
}

.platform-demo .platform svg {
  width: 28px;
  height: 28px;
}

.platform-demo .platform-jvm { left: 10%; transform: translateX(-50%); }
.platform-demo .platform-js { left: 36%; transform: translateX(-50%); }
.platform-demo .platform-wasm { left: 62%; transform: translateX(-50%); }
.platform-demo .platform-llvm { left: 88%; transform: translateX(-50%); font-size: 0.75rem; font-weight: bold; }

/* Type safety animation */
.type-safety-demo {
  position: relative;
  width: 220px;
  height: 85px;
  font-family: var(--mono, monospace);
  font-size: 0.55rem;
  margin: 0 auto;
}

.type-safety-demo .node {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.1rem;
}

.type-safety-demo .node-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
}

.type-safety-demo .node-label {
  font-size: 0.5rem;
  font-weight: 600;
  opacity: 0.8;
}

.type-safety-demo .node-type {
  font-size: 0.4rem;
  opacity: 0.5;
  white-space: nowrap;
}

.type-safety-demo .node-e { left: 10px; top: 18px; }
.type-safety-demo .node-t { left: 95px; top: 18px; animation: node-t-fade 6s ease-out infinite; }
.type-safety-demo .node-l { left: 180px; top: 18px; animation: node-l-move 6s ease-out infinite; }

.type-safety-demo .conn {
  position: absolute;
  top: 24px;
  font-size: 0.5rem;
  opacity: 0.4;
  color: var(--md-primary-fg-color);
}

.type-safety-demo .conn-et { left: 72px; transform: translateX(-50%); animation: conn-et-fade 6s ease-out infinite; }
.type-safety-demo .conn-tl { left: 158px; transform: translateX(-50%); animation: conn-tl-fade 6s ease-out infinite; }

.type-safety-demo .flow-dot {
  position: absolute;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--md-primary-fg-color);
  box-shadow: 0 0 6px var(--md-primary-fg-color);
  opacity: 0;
  top: 21px;
}

.type-safety-demo .flow-dot-1 { animation: flow-good 6s ease-out infinite; }
.type-safety-demo .flow-dot-2 { animation: flow-good 6s ease-out infinite 0.15s; }

.type-safety-demo .flow-bad {
  position: absolute;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 6px #ef4444;
  opacity: 0;
  top: 21px;
  animation: flow-fail 6s ease-out infinite;
}


.type-safety-demo .result {
  position: absolute;
  top: 60px;
  font-size: 0.5rem;
  opacity: 0;
}

.type-safety-demo .result-fail {
  left: 60px;
  top: 68px;
  color: #ef4444;
  white-space: nowrap;
  animation: result-fail-anim 6s ease-out infinite;
}

.type-safety-demo .node-dot {
  animation: node-pulse-green 6s ease-out infinite;
}

@keyframes node-pulse-green {
  0%, 30% { box-shadow: none; background: var(--md-primary-fg-color); }
  35%, 42% { box-shadow: 0 0 8px var(--md-primary-fg-color); background: var(--md-primary-fg-color); }
  47%, 68% { box-shadow: none; background: var(--md-primary-fg-color); }
  73%, 88% { box-shadow: 0 0 8px #ef4444; background: #ef4444; }
  93%, 100% { box-shadow: none; background: var(--md-primary-fg-color); }
}

@keyframes node-t-fade {
  0%, 42% { opacity: 1; }
  50%, 88% { opacity: 0; }
  95%, 100% { opacity: 1; }
}

@keyframes node-l-move {
  0%, 42% { left: 180px; }
  50%, 88% { left: 95px; }
  95%, 100% { left: 180px; }
}

@keyframes conn-tl-fade {
  0%, 42% { opacity: 0.4; }
  50%, 88% { opacity: 0; }
  95%, 100% { opacity: 0.4; }
}

@keyframes conn-et-fade {
  0%, 42% { opacity: 0.4; color: var(--md-primary-fg-color); }
  50%, 70% { opacity: 0.6; color: #ef4444; }
  75%, 88% { opacity: 0.4; color: #ef4444; }
  95%, 100% { opacity: 0.4; color: var(--md-primary-fg-color); }
}

@keyframes flow-good {
  0%, 5% { left: 18px; opacity: 0; }
  8% { opacity: 0.9; }
  18% { left: 100px; opacity: 0.9; }
  32% { left: 185px; opacity: 0.9; }
  36%, 100% { left: 185px; opacity: 0; }
}

@keyframes flow-fail {
  0%, 50% { left: 18px; opacity: 0; }
  53% { left: 18px; opacity: 0.9; }
  65% { left: 70px; opacity: 0.9; }
  70% { left: 75px; opacity: 0; }
  100% { opacity: 0; }
}

@keyframes result-ok-anim {
  0%, 32% { opacity: 0; }
  38%, 42% { opacity: 1; }
  47%, 100% { opacity: 0; }
}

@keyframes result-fail-anim {
  0%, 68% { opacity: 0; }
  73%, 88% { opacity: 1; }
  93%, 100% { opacity: 0; }
}

/* Typing animation */
.typing-demo {
  font-family: var(--mono, monospace);
  font-size: 13px;
  line-height: 1.5;
  color: var(--md-code-fg-color);
  background: transparent;
  padding: 0.5em 0;
  overflow: hidden;
  width: 280px;
  margin: 0 auto;
}

.typing-demo .line {
  white-space: pre;
  overflow: hidden;
  width: 0;
  opacity: 0;
}

.typing-demo .line-1 { animation: type-line1 8s steps(24, end) infinite; }
.typing-demo .line-2 { animation: type-line2 8s steps(14, end) infinite; }
.typing-demo .line-3 { animation: type-line3 8s steps(1, end) infinite; }
.typing-demo .line-4 { animation: type-line4 8s steps(14, end) infinite; }
.typing-demo .line-5 { animation: type-line5 8s steps(28, end) infinite; }

.typing-demo .c { color: var(--md-code-hl-comment-color); }
.typing-demo .k { color: var(--md-code-hl-keyword-color); }

@keyframes type-line1 {
  0%, 2% { width: 0; opacity: 1; }
  12% { width: 24ch; opacity: 1; }
  85%, 100% { width: 24ch; opacity: 1; }
}

@keyframes type-line2 {
  0%, 14% { width: 0; opacity: 0; }
  15% { width: 0; opacity: 1; }
  25% { width: 14ch; opacity: 1; }
  85%, 100% { width: 14ch; opacity: 1; }
}

@keyframes type-line3 {
  0%, 27% { width: 0; opacity: 0; }
  28% { width: 1ch; opacity: 1; }
  85%, 100% { width: 1ch; opacity: 1; }
}

@keyframes type-line4 {
  0%, 30% { width: 0; opacity: 0; }
  31% { width: 0; opacity: 1; }
  42% { width: 14ch; opacity: 1; }
  85%, 100% { width: 14ch; opacity: 1; }
}

@keyframes type-line5 {
  0%, 44% { width: 0; opacity: 0; }
  45% { width: 0; opacity: 1; }
  65% { width: 100%; opacity: 1; }
  85%, 100% { width: 100%; opacity: 1; }
}

@keyframes drop-to-jvm {
  0% { top: 48px; left: 50%; opacity: 0; transform: scale(0.5); }
  8% { opacity: 0.8; transform: scale(1); }
  95% { opacity: 0.8; transform: scale(1); }
  100% { top: 110px; left: 10%; opacity: 0; transform: scale(0.5); }
}

@keyframes drop-to-js {
  0% { top: 48px; left: 50%; opacity: 0; transform: scale(0.5); }
  8% { opacity: 0.8; transform: scale(1); }
  95% { opacity: 0.8; transform: scale(1); }
  100% { top: 110px; left: 36%; opacity: 0; transform: scale(0.5); }
}

@keyframes drop-to-wasm {
  0% { top: 48px; left: 50%; opacity: 0; transform: scale(0.5); }
  8% { opacity: 0.8; transform: scale(1); }
  95% { opacity: 0.8; transform: scale(1); }
  100% { top: 110px; left: 62%; opacity: 0; transform: scale(0.5); }
}

@keyframes drop-to-llvm {
  0% { top: 48px; left: 50%; opacity: 0; transform: scale(0.5); }
  8% { opacity: 0.8; transform: scale(1); }
  95% { opacity: 0.8; transform: scale(1); }
  100% { top: 110px; left: 88%; opacity: 0; transform: scale(0.5); }
}

.intro-header h1 {
  font-size: 2.5rem;
  font-weight: 200;
  margin: 0.5rem 0;
  border: none !important;
  padding: 0 !important;
}

.intro-header p {
  font-size: 1.1rem;
  margin: 0.5rem 0 1.5rem 0;
  opacity: 0.9;
}

.intro-buttons {
  display: flex;
  gap: 1.5rem;
  justify-content: center;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}

.intro-buttons a {
  padding: 0.45rem 1rem;
  text-decoration: none !important;
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--md-primary-fg-color);
  background: transparent;
  border: 1px solid var(--md-primary-fg-color);
  border-radius: 4px;
  transition: all 0.15s ease;
}

.intro-buttons a:first-child {
  background: var(--md-primary-fg-color);
  color: #fff;
}

.intro-buttons a:hover {
  background: var(--md-primary-fg-color);
  color: #fff;
}

.intro-buttons a:first-child:hover {
  opacity: 0.85;
}

/* How it works - 1, 2, 3 */
.how-it-works-header {
  text-align: center;
  font-size: 0.8rem;
  font-weight: 600;
  margin-bottom: 1.25rem;
  opacity: 0.9;
}

.how-it-works {
  display: flex;
  justify-content: center;
  gap: 2.5rem;
  margin: 0 0 2rem 0;
  flex-wrap: wrap;
}

.step {
  text-align: center;
  min-width: 160px;
  max-width: 200px;
}

.step-num {
  font-size: 1.5rem;
  opacity: 0.3;
  margin-bottom: 0.25rem;
}

.step-title {
  font-size: 0.85rem;
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.step-desc {
  font-size: 0.7rem;
  opacity: 0.7;
  line-height: 1.4;
}

/* Quotes section */
.quotes-section {
  max-width: 520px;
  margin: 2rem auto;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.quotes-section .quote-item {
  position: relative;
  font-size: 0.75rem;
  font-style: italic;
  opacity: 0.85;
  line-height: 1.6;
  padding-left: 1.5rem;
}

.quotes-section .quote-item::before {
  content: "\201C";
  position: absolute;
  left: -0.25rem;
  top: -0.75rem;
  font-size: 3rem;
  font-family: Georgia, serif;
  color: var(--md-primary-fg-color);
  opacity: 0.3;
  line-height: 1;
}

.quotes-section .quote-item cite {
  font-style: normal;
  opacity: 0.5;
  display: inline-block;
  margin-top: 0.4rem;
}

/* Motivation */
.motivation {
  margin: 2.5rem 0 1.5rem 0;
}

.motivation h4 {
  font-size: 0.8rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.motivation p {
  font-size: 0.75rem;
  line-height: 1.6;
  opacity: 0.8;
}

/* Get started */
.get-started {
  text-align: center;
  margin: 2.5rem 0 6rem 0;
  padding-bottom: 2rem;
}

.instacart-note {
  font-size: 0.75rem;
  opacity: 0.6;
  margin: 0 0 1rem 0;
}

.instacart-note a {
  color: inherit;
}

.get-started-links {
  display: flex;
  justify-content: center;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.get-started-links a {
  font-size: 0.75rem;
  color: var(--md-default-fg-color);
  text-decoration: none;
  opacity: 0.6;
  transition: opacity 0.15s ease;
}

.get-started-links a:hover {
  opacity: 1;
}

/* Zen dividers */
.md-typeset hr {
  margin: 1.25rem auto;
  border: none;
  height: 1px;
  background: var(--md-default-fg-color--lightest);
  max-width: 120px;
}

/* Feature grid - Elm style */
.feature-grid {
  margin: 2rem 0;
}

.feature-row {
  display: flex;
  gap: 2rem;
  align-items: center;
  margin: 2.5rem 0;
}

.feature-row.reverse {
  flex-direction: row-reverse;
}

.feature-text {
  flex: 1.2;
}

.feature-text h3 {
  font-size: 0.85rem;
  font-weight: 600;
  margin: 0 0 0.4rem 0;
  color: var(--md-default-fg-color) !important;
  opacity: 1 !important;
}

.feature-text p {
  font-size: 0.75rem;
  line-height: 1.5;
  margin: 0;
  opacity: 0.9;
}

.feature-visual {
  flex: 0.8;
}

.feature-visual pre {
  margin: 0 !important;
  font-size: 0.8rem !important;
}

.feature-visual.quote blockquote {
  margin: 0;
  padding: 1rem 1.25rem;
  background: none;
  border: none;
  font-style: italic;
  font-size: 0.9rem;
  line-height: 1.5;
  position: relative;
}

.feature-visual.quote blockquote::before {
  content: "\201D";
  position: absolute;
  top: -1.5rem;
  left: -0.5rem;
  font-size: 8rem;
  font-family: Georgia, serif;
  color: var(--md-primary-fg-color);
  opacity: 0.2;
  line-height: 1;
  pointer-events: none;
  z-index: 0;
}

.feature-visual.quote blockquote > * {
  position: relative;
  z-index: 1;
}

.feature-visual.quote cite {
  display: block;
  margin-top: 0.75rem;
  font-size: 0.8rem;
  opacity: 0.7;
  font-style: normal;
}

/* Mobile adjustments */
@media (max-width: 768px) {
  .intro-header h1 {
    font-size: 2rem;
  }

  .intro-header p {
    font-size: 1rem;
  }

  .intro-buttons {
    flex-direction: column;
    align-items: stretch;
    max-width: 300px;
    margin-left: auto;
    margin-right: auto;
  }

  .intro-buttons a {
    text-align: center;
  }

  .feature-row,
  .feature-row.reverse {
    flex-direction: column;
    gap: 1rem;
  }

  .feature-text,
  .feature-visual {
    width: 100%;
  }
}
</style>

<div class="intro-header">
  <img src="assets/etl4s-logo.png" alt="etl4s" />
  <h1>etl4s</h1>
  <p style="opacity: 0.6; font-size: 0.85rem; margin: 0.5rem 0 1.5rem 0;">Powerful, whiteboard-style ETL.</p>
  <div class="intro-buttons">
    <a href="installation/" class="btn-primary">Get Started</a>
    <a href="https://scastie.scala-lang.org/mattlianje/1280QhQ5RWODgizeXOIsXA/5" target="_blank" class="btn-secondary">Try Online</a>
    <a href="https://github.com/mattlianje/etl4s" target="_blank" class="btn-secondary">GitHub</a>
  </div>
</div>

=== "Chain"

    ```scala
    import etl4s._

    val fiveExtract      = Extract(5)
    val timesTwo         = Transform[Int, Int](_ * 2)
    val plusFive         = Transform[Int, Int](_ + 5)
    val timesTwoPlusFive = timesTwo `andThen` plusFive
    val exclaim          = Transform[Int, String](x => s"Result: $x!")
    val consoleLoad      = Load[String, Unit](println)
    val dbLoad           = Load[String, Unit](x => println(s"[DB] $x"))

    val pipeline = fiveExtract ~> timesTwoPlusFive ~> exclaim ~> (consoleLoad & dbLoad)

    pipeline.unsafeRun()
    // Result: 15!
    // [DB] Result: 15!
    ```

=== "Config"

    ```scala
    import etl4s._

    case class Env(path: String)

    val load = Load[String, Unit].requires[Env] { env => data =>
      println(s"Writing to ${env.path}")
    }

    val pipeline = extract ~> transform ~> load

    pipeline.provide(Env("s3://dev")).unsafeRun()
    pipeline.provide(Env("s3://prod")).unsafeRun()
    ```

=== "Diagram"

    ```scala
    import etl4s._

    val A = Node[String, String](identity)
      .lineage(name = "A", inputs = List("s1", "s2"), outputs = List("s3"))

    val B = Node[String, String](identity)
      .lineage(name = "B", inputs = List("s3"), outputs = List("s4", "s5"))

    Seq(A, B).toMermaid
    ```

    ```mermaid
    graph LR
        classDef pipeline fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#000
        classDef dataSource fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#000

        A["A"]
        B["B"]
        s1(["s1"])
        s2(["s2"])
        s3(["s3"])
        s4(["s4"])
        s5(["s5"])

        s1 --> A
        s2 --> A
        A --> s3
        s3 --> B
        B --> s4
        B --> s5

        class A pipeline
        class B pipeline
        class s1,s2,s3,s4,s5 dataSource
    ```

=== "Telemetry"

    ```scala
    import etl4s._

    val process = Transform[List[Row], List[Row]] { rows =>
      Tel.addCounter("rows.processed", rows.size)
      Tel.setGauge("batch.size", rows.size.toDouble)
      rows.filter(_.isValid)
    }

    process.unsafeRun(rows)  // no-ops (zero cost)

    implicit val t: Etl4sTelemetry = Prometheus()
    process.unsafeRun(rows)  // metrics flowing
    ```

---

<div class="how-it-works-header">How it works</div>
<div class="how-it-works">
  <div class="step">
    <div class="step-num">1</div>
    <div class="step-title">Import</div>
    <div class="step-desc">Drop one file into your project. No dependencies, no framework lock-in.</div>
  </div>
  <div class="step">
    <div class="step-num">2</div>
    <div class="step-title">Chain</div>
    <div class="step-desc">Connect nodes with <code>~></code>, branch with <code>&</code>, inject config with <code>.requires</code></div>
  </div>
  <div class="step">
    <div class="step-num">3</div>
    <div class="step-title">Run</div>
    <div class="step-desc">Call <code>.unsafeRun()</code>. Works in scripts, Spark, Flink, anywhere Scala runs.</div>
  </div>
</div>

---

<div class="feature-grid">

<div class="feature-row">
<div class="feature-text">
<h3>Pipelines as values.</h3>
<p>One file, zero dependencies. Lazy, composable, testable. Since pipelines are values, attach metadata, generate lineage diagrams, share them across teams.</p>
</div>
<div class="feature-visual">
<div class="typing-demo">
<div class="line line-1"><span class="c">// One import. That's it.</span></div>
<div class="line line-2"><span class="k">import</span> etl4s._</div>
<div class="line line-3"> </div>
<div class="line line-4"><span class="k">val</span> pipeline =</div>
<div class="line line-5">  extract ~> transform ~> load</div>
</div>
</div>
</div>

<div class="feature-row reverse">
<div class="feature-text">
<h3>Type-safe composition.</h3>
<p>Types must align or it won't compile. Misconnections are compile errors.</p>
</div>
<div class="feature-visual">
<div class="type-safety-demo">
  <div class="node node-e"><span class="node-dot"></span><span class="node-label">E</span><span class="node-type">[A, Int]</span></div>
  <span class="conn conn-et">~></span>
  <div class="node node-t"><span class="node-dot"></span><span class="node-label">T</span><span class="node-type">[Int, Str]</span></div>
  <span class="conn conn-tl">~></span>
  <div class="node node-l"><span class="node-dot"></span><span class="node-label">L</span><span class="node-type">[Str, B]</span></div>
  <span class="flow-dot flow-dot-1"></span>
  <span class="flow-dot flow-dot-2"></span>
  <span class="flow-bad"></span>
  <span class="result result-fail">won't compile</span>
</div>
</div>
</div>

<div class="feature-row">
<div class="feature-text">
<h3>Dependency injection, inferred.</h3>
<p>Nodes declare what they need. Chain freely. The compiler merges and infers the combined type.</p>
</div>
<div class="feature-visual">
<div class="env-merge-demo">
  <div class="env env-a">
    <span class="dot"></span>
    <span class="env-label">Needs[Db]</span>
  </div>
  <span class="merge-op">~></span>
  <div class="env env-b">
    <span class="dot"></span>
    <span class="env-label">Needs[Api]</span>
  </div>
  <span class="merge-eq">=</span>
  <div class="env env-result">
    <span class="dot"></span>
    <span class="env-label">Needs[Db & Api]</span>
  </div>
</div>
</div>
</div>

<div class="feature-row reverse">
<div class="feature-text">
<h3>Built-in tracing.</h3>
<p>Shared execution state across pipeline nodes. Write logs, flag errors, react to upstream failures, track timing. Retrieve with <code>.unsafeRunTrace()</code>.</p>
</div>
<div class="feature-visual">
<div class="trace-demo">
  <div class="pipeline">
    <div class="node-group">
      <div class="trace-node trace-node-1"></div>
      <span class="node-label">E</span>
      <div class="node-connector write-conn-1"></div>
      <span class="node-label-action write write-label-1">log</span>
    </div>
    <span class="trace-arrow">~></span>
    <div class="node-group">
      <div class="trace-node trace-node-2"></div>
      <span class="node-label">T</span>
      <div class="node-connector read read-conn-2"></div>
      <span class="node-label-action read read-label-2">check</span>
      <div class="node-connector write-conn-2"></div>
      <span class="node-label-action write write-label-2">log</span>
    </div>
    <span class="trace-arrow">~></span>
    <div class="node-group">
      <div class="trace-node trace-node-3"></div>
      <span class="node-label">L</span>
      <div class="node-connector read read-conn-3"></div>
      <span class="node-label-action read read-label-3">check</span>
    </div>
  </div>
  <span class="trace-dot trace-dot-1"></span>
  <span class="trace-dot trace-dot-2"></span>
  <span class="trace-dot trace-dot-3 bad"></span>
  <span class="channel-title">Trace</span>
  <div class="channel">
    <div class="channel-logs">
      <span class="channel-label">logs</span>
      <span class="log-entry log-1">"read 1420 rows"</span>
      <span class="log-entry log-2">"validated 89"</span>
    </div>
    <div class="channel-time">
      <span class="channel-label">time</span>
      <div class="timer-display">
        <span class="timer-num">
          <span class="timer-val timer-0">0</span>
          <span class="timer-val timer-1">1</span>
          <span class="timer-val timer-2">2</span>
          <span class="timer-val timer-3">3</span>
          <span class="timer-val timer-4">4</span>
        </span>
        <span class="timer-unit">ms</span>
      </div>
    </div>
  </div>
  <div class="trace-output">
    <span class="output-arrow">→</span>
    <div class="trace-result">
      <div class="trace-result-inner">
        <span class="trace-result-title">Trace[B]</span>
        <span class="trace-result-line">result: <span class="val">B</span></span>
        <span class="trace-result-line">logs: <span class="val">2</span></span>
        <span class="trace-result-line">errors: <span class="val err">1</span></span>
        <span class="trace-result-line">time: <span class="val">4ms</span></span>
      </div>
    </div>
  </div>
</div>
</div>
</div>

</div>

<div class="feature-row reverse">
<div class="feature-text">
<h3>Runs anywhere.</h3>
<p>JVM, JavaScript, WebAssembly, native binaries via LLVM. Same code, zero platform-specific APIs.</p>
</div>
<div class="feature-visual">
<div class="platform-demo">
  <img src="assets/etl4s-logo.png" alt="" class="logo-source" />
  <span class="drop drop-jvm-1"></span>
  <span class="drop drop-jvm-2"></span>
  <span class="drop drop-jvm-3"></span>
  <span class="drop drop-jvm-4"></span>
  <span class="drop drop-js-1"></span>
  <span class="drop drop-js-2"></span>
  <span class="drop drop-js-3"></span>
  <span class="drop drop-js-4"></span>
  <span class="drop drop-wasm-1"></span>
  <span class="drop drop-wasm-2"></span>
  <span class="drop drop-wasm-3"></span>
  <span class="drop drop-wasm-4"></span>
  <span class="drop drop-llvm-1"></span>
  <span class="drop drop-llvm-2"></span>
  <span class="drop drop-llvm-3"></span>
  <span class="drop drop-llvm-4"></span>
  <span class="platform platform-jvm"><svg viewBox="0 0 128 128" width="24" height="24"><path fill="#EA2D2E" d="M47.617 98.12s-4.767 2.774 3.397 3.71c9.892 1.13 14.947.968 25.845-1.092 0 0 2.871 1.795 6.873 3.351-24.439 10.47-55.308-.607-36.115-5.969zm-2.988-13.665s-5.348 3.959 2.823 4.805c10.567 1.091 18.91 1.18 33.354-1.6 0 0 1.993 2.025 5.132 3.131-29.542 8.64-62.446.68-41.309-6.336z"/><path fill="#EA2D2E" d="M69.802 61.271c6.025 6.935-1.58 13.17-1.58 13.17s15.289-7.891 8.269-17.777c-6.559-9.215-11.587-13.792 15.635-29.58 0 .001-42.731 10.67-22.324 34.187z"/><path fill="#EA2D2E" d="M102.123 108.229s3.529 2.91-3.888 5.159c-14.102 4.272-58.706 5.56-71.094.171-4.451-1.938 3.899-4.625 6.526-5.192 2.739-.593 4.303-.485 4.303-.485-4.953-3.487-32.013 6.85-13.743 9.815 49.821 8.076 90.817-3.637 77.896-9.468zM49.912 70.294s-22.686 5.389-8.033 7.348c6.188.828 18.518.638 30.011-.326 9.39-.789 18.813-2.474 18.813-2.474s-3.308 1.419-5.704 3.053c-23.042 6.061-67.544 3.238-54.731-2.958 10.832-5.239 19.644-4.643 19.644-4.643zm40.697 22.747c23.421-12.167 12.591-23.86 5.032-22.285-1.848.385-2.677.72-2.677.72s.688-1.079 2-1.543c14.953-5.255 26.451 15.503-4.823 23.725 0-.002.359-.327.468-.617z"/><path fill="#EA2D2E" d="M76.491 1.587S89.459 14.563 64.188 34.51c-20.266 16.006-4.621 25.13-.007 35.559-11.831-10.673-20.509-20.07-14.688-28.815C58.041 28.42 81.722 22.195 76.491 1.587z"/><path fill="#EA2D2E" d="M52.214 126.021c22.476 1.437 57-.8 57.817-11.436 0 0-1.571 4.032-18.577 7.231-19.186 3.612-42.854 3.191-56.887.874 0 .001 2.875 2.381 17.647 3.331z"/></svg></span>
  <span class="platform platform-js"><svg viewBox="0 0 128 128" width="24" height="24"><path fill="#F0DB4F" d="M1.408 1.408h125.184v125.185H1.408z"/><path fill="#323330" d="M116.347 96.736c-.917-5.711-4.641-10.508-15.672-14.981-3.832-1.761-8.104-3.022-9.377-5.926-.452-1.69-.512-2.642-.226-3.665.821-3.32 4.784-4.355 7.925-3.403 2.023.678 3.938 2.237 5.093 4.724 5.402-3.498 5.391-3.475 9.163-5.879-1.381-2.141-2.118-3.129-3.022-4.045-3.249-3.629-7.676-5.498-14.756-5.355l-3.688.477c-3.534.893-6.902 2.748-8.877 5.235-5.926 6.724-4.236 18.492 2.975 23.335 7.104 5.332 17.54 6.545 18.873 11.531 1.297 6.104-4.486 8.08-10.234 7.378-4.236-.881-6.592-3.034-9.139-6.949-4.688 2.713-4.688 2.713-9.508 5.485 1.143 2.499 2.344 3.63 4.26 5.795 9.068 9.198 31.76 8.746 35.83-5.176.165-.478 1.261-3.666.38-8.581zM69.462 58.943H57.753l-.048 30.272c0 6.438.333 12.34-.714 14.149-1.713 3.558-6.152 3.117-8.175 2.427-2.059-1.012-3.106-2.451-4.319-4.485-.333-.584-.583-1.036-.667-1.071l-9.52 5.83c1.583 3.249 3.915 6.069 6.902 7.901 4.462 2.678 10.459 3.499 16.731 2.059 4.082-1.189 7.604-3.652 9.448-7.401 2.666-4.915 2.094-10.864 2.07-17.444.06-10.735.001-21.468.001-32.237z"/></svg></span>
  <span class="platform platform-wasm"><svg viewBox="0 0 128 128" width="24" height="24"><path fill="#654FF0" d="M0 0h128v128H0z"/><text x="64" y="80" fill="#fff" font-family="Arial,sans-serif" font-size="36" font-weight="bold" text-anchor="middle">WA</text></svg></span>
  <span class="platform platform-llvm">LLVM</span>
</div>
</div>
</div>

<div class="motivation">
<h4>Why etl4s?</h4>
<p>Chaotic, framework-coupled ETL codebases drive dev teams to their knees. etl4s lets you structure your code as clean, typed graphs of pure functions.</p>
</div>

<div class="quotes-section">
<p class="quote-item">(~>) is just *chef's kiss*. There are so many synergies here, haven't pushed for something this hard in a while.<br><cite>Sr Engineering Manager, Instacart</cite></p>
<p class="quote-item">...the advantages of full blown effect systems without the complexities, and awkward monad syntax!<br><cite>u/RiceBroad4552</cite></p>
</div>

<div class="get-started">
<p class="instacart-note">Battle-tested at <a href="https://www.instacart.com/">Instacart</a> 🥕</p>
<div class="get-started-links">
<a href="installation/">Installation</a>
<a href="first-pipeline/">First Pipeline</a>
<a href="core-concepts/">Core Concepts</a>
<a href="examples/">Examples</a>
</div>
</div>
