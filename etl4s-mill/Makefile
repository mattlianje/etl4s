# etl4s Mill Build

.PHONY: compile test test-jvm test-js test-native publish-local bundle clean fmt fmt-check repl

VERSION := 1.8.0
BUNDLE_DIR := bundles
GPG_KEY := F36FE8EEBD829E6CF1A5ADB6246482D1268EDC6E

# Compile all versions (JVM + JS + Native)
compile:
	./mill etl4s.__.compile

# Start a REPL with etl4s loaded (Scala 3 JVM)
repl:
	./mill -i etl4s.jvm[3.5.2].console

# Run all tests
test: test-jvm test-js test-native

# JVM tests
test-jvm:
	./mill etl4s.jvm.__.test

# JS tests
test-js:
	./mill etl4s.js.__.test

# Native tests
test-native:
	./mill etl4s.native.__.test

# Publish to local ivy repo (unsigned)
publish-local:
	./mill etl4s.__.publishLocal

# Format code with scalafmt
fmt:
	./mill mill.scalalib.scalafmt/

# Check formatting without modifying
fmt-check:
	./mill mill.scalalib.scalafmt/ --check

# Clean build artifacts
clean:
	./mill clean
	rm -rf $(BUNDLE_DIR)

# Create signed bundle zip locally (for manual upload to Sonatype Central)
bundle:
	@echo "Building publish artifacts..."
	@./mill show etl4s.__.publishArtifacts > /dev/null
	@rm -rf $(BUNDLE_DIR) && mkdir -p $(BUNDLE_DIR)/xyz/matthieucourt
	@for platform in jvm js native; do \
		for scalaVer in 2.12.20 2.13.16 3.5.2; do \
			artifactId=$$(./mill show etl4s.$$platform[$$scalaVer].artifactId 2>/dev/null | tr -d '"'); \
			dir=$(BUNDLE_DIR)/xyz/matthieucourt/$$artifactId/$(VERSION); \
			mkdir -p $$dir; \
			cp out/etl4s/$$platform/$$scalaVer/pom.dest/*.pom $$dir/$$artifactId-$(VERSION).pom; \
			cp out/etl4s/$$platform/$$scalaVer/jar.dest/out.jar $$dir/$$artifactId-$(VERSION).jar; \
			cp out/etl4s/$$platform/$$scalaVer/sourceJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-sources.jar; \
			cp out/etl4s/$$platform/$$scalaVer/docJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-javadoc.jar; \
			for f in $$dir/*; do gpg --batch --yes -ab -u $(GPG_KEY) $$f; done; \
			echo "Packaged $$artifactId"; \
		done; \
	done
	@cd $(BUNDLE_DIR) && zip -r etl4s-$(VERSION)-bundle.zip xyz
	@rm -rf $(BUNDLE_DIR)/xyz
	@echo "\nBundle ready: $(BUNDLE_DIR)/etl4s-$(VERSION)-bundle.zip"
