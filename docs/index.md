---
hide:
  - navigation
  - toc
  - path
  - header
  - footer
  - title
---

<style>

/* Global overrides */
code,
pre,
pre code,
.md-typeset code,
.md-typeset pre code {
  font-family: "JetBrains Mono", monospace !important;
  font-size: 0.7rem !important;
  line-height: 1.4 !important;
}

/* Completely hide MkDocs top nav bar */
.md-header {
  display: none !important;
}

.md-content, .md-main__inner {
  max-width: 100% !important;
  padding: 0 !important;
  margin: 0 !important;
}

/* Full-width hero wrapper */
.hero-wrapper {
  width: 100vw;
  margin-left: calc(-50vw + 50%);
}

/* Hero banner */
.hero {
  background: var(--md-primary-fg-color);
  color: var(--md-primary-bg-color);
  text-align: center;
  padding: 4rem 1rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  animation: fadeIn 0.6s ease-out both;
}
.hero h1 {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}
.hero p {
  font-size: 1.4rem;
  margin-bottom: 2rem;
}
.hero-buttons a {
  display: inline-block;
  margin: 0 0.35rem;
  padding: 0.4rem 1rem;
  font-weight: 500;
  font-size: 0.8rem;
  border-radius: 0.35rem;
  text-decoration: none;
  background: var(--md-accent-fg-color);
  color: var(--md-default-bg-color);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
  transition: all 0.2s ease;
}
.hero-buttons a:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  background: var(--md-accent-fg-color--lighter, #d1c4e9);
  color: var(--md-primary-fg-color);
}

.hero-buttons a.github-btn {
  background: transparent;
  color: #24292e;
  border: 1px solid #ccc;
}

.hero-buttons a.github-btn:hover {
  background: #f6f8fa;
  color: #111;
  border-color: #bbb;
}
.github-icon {
  width: 14px;
  height: 14px;
  fill: currentColor;
  margin-right: 0.4rem;
  vertical-align: middle;
  position: relative;
  top: -0.5px;
}

/* Feature tiles row */
.features {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 2rem;
  margin: 4rem auto;
  max-width: 960px;
  text-align: center;
  padding: 0 2rem;
}
.feature-box {
  flex: 1 1 250px;
  padding: 1rem;
  background: var(--md-default-bg-color);
  border-radius: 0.75rem;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
  transition: transform 0.2s;
}
.feature-box:hover {
  transform: translateY(-4px);
}
.feature-box h3 {
  font-size: 1.25rem;
  margin-bottom: 0.5rem;
  color: var(--md-primary-fg-color);
}

/* Fade-in */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Mobile */
@media (max-width: 768px) {
  .hero h1 {
    font-size: 2.2rem;
  }
  .hero p {
    font-size: 1.1rem;
  }
}

@media (max-width: 768px) {
  .hero-buttons {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.75rem;
  }

  .hero-buttons a.github-btn {
    width: auto;
    margin: 0.5rem auto 0;
    display: inline-flex;
    justify-content: center;
  }
}

/* Final padding + margin */
html, body, .md-main, .md-main__inner, .md-content, .md-container, main, article {
  margin: 0 !important;
  padding: 0 !important;
  border: 0 !important;
}

body::before,
.md-container::before,
.md-main::before,
.md-main__inner::before,
.md-content::before,
main::before,
article::before {
  display: none !important;
  content: none !important;
}

body {
  scroll-padding-top: 0 !important;
  font-weight: 400;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>

<!-- Hero splash full-width -->
<div class="hero-wrapper">
  <div class="hero">
    <img src="assets/etl4s-logo.png" alt="etl4s logo"
         style="height: 72px; margin-bottom: 0.75rem;" />
    <h1>etl4s</h1>
    <p>Powerful, whiteboard-style ETL</p>

    <div class="hero-buttons">
      <a href="https://scastie.scala-lang.org/mattlianje/1280QhQ5RWODgizeXOIsXA/5"
      target="_blank">TRY IT!</a>
      <a href="installation/">GET STARTED</a>
<a href="https://github.com/mattlianje/etl4s" target="_blank" class="github-btn">
  <svg class="github-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
    <path fill-rule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 
      2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 
      0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52
      -.01-.53.63-.01 1.08.58 1.23.82.72 1.21 
      1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78
      -.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15
      -.08-.2-.36-1.02.08-2.12 0 0 .67-.21 
      2.2.82a7.56 7.56 0 012-.27c.68 0 1.36.09 
      2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 
      1.92.08 2.12.51.56.82 1.27.82 2.15 0 
      3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 
      1.48 0 1.07-.01 1.93-.01 2.2 0 
      .21.15.46.55.38A8.013 8.013 0 0016 
      8c0-4.42-3.58-8-8-8z"/>
  </svg>
  GITHUB
</a>
    </div>
  </div>
</div>

<div style="max-width: 700px; margin: 2rem auto 0 auto;">
```scala
import etl4s._

/* Define your building blocks */
val fiveExtract = Extract(5)
val timesTwo    = Transform[Int, Int](_ * 2)
val exclaim     = Transform[Int, String](n => s"$n!")
val consoleLoad = Load[String, Unit](println(_))

/* Add config with .requires */
val dbLoad      = Load[String, Unit].requires[String] { dbType => s =>
  println(s"Saving to $dbType DB: $s")
}

/* Stitch your pipeline */
val pipeline =
  fiveExtract ~> timesTwo ~> exclaim ~> (consoleLoad &> dbLoad)

/* Provide config, then run*/
pipeline.provide("sqlite").unsafeRun(())
```
</div>

<!-- Feature callouts -->
<div class="features">
  <div class="feature-box">
    <h3>Whiteboard-style</h3>
    <p>Model pipelines like you think - with visual, readable chaining</p>
  </div>
  <div class="feature-box">
    <h3>Config-driven</h3>
    <p>Your pipelines are typed, declarative endpoints - easy to compose and trigger</p>
  </div>
  <div class="feature-box">
    <h3>Type-safe</h3>
    <p>Prevent bugs by catching type mismatches at compile time</p>
  </div>
</div>
<div style="height: 3rem;"></div>
