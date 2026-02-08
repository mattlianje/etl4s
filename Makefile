.PHONY: compile test test-jvm test-js test-native publish-local bundle clean fmt fmt-check repl repl2 docs publish-doc

VERSION := 1.9.0
BUNDLE_DIR := bundles
GPG_KEY := F36FE8EEBD829E6CF1A5ADB6246482D1268EDC6E

# Compile all versions (JVM + JS + Native)
compile:
	./mill etl4s.__.compile

repl:
	./mill -i etl4s.jvm[3.3.7].console

repl2:
	./mill -i etl4s.jvm[2.13.10].console

test: test-jvm test-js test-native

test-jvm:
	./mill etl4s.jvm.__.test

test-js:
	./mill etl4s.js.__.test

test-native:
	./mill etl4s.native.__.test

publish-local:
	./mill etl4s.__.publishLocal

fmt:
	./mill mill.scalalib.scalafmt/

fmt-check:
	./mill mill.scalalib.scalafmt/ --check

clean:
	./mill clean
	rm -rf $(BUNDLE_DIR)

bundle:
	@echo "Building publish artifacts..."
	@./mill show etl4s.__.publishArtifacts > /dev/null
	@rm -rf $(BUNDLE_DIR) && mkdir -p $(BUNDLE_DIR)/xyz/matthieucourt
	@for platform in jvm js native; do \
		for scalaVer in 2.12.17 2.13.10 3.3.7; do \
			artifactId=$$(./mill show etl4s.$$platform[$$scalaVer].artifactId 2>/dev/null | tr -d '"'); \
			dir=$(BUNDLE_DIR)/xyz/matthieucourt/$$artifactId/$(VERSION); \
			mkdir -p $$dir; \
			cp out/etl4s/$$platform/$$scalaVer/pom.dest/*.pom $$dir/$$artifactId-$(VERSION).pom; \
			cp out/etl4s/$$platform/$$scalaVer/jar.dest/out.jar $$dir/$$artifactId-$(VERSION).jar; \
			cp out/etl4s/$$platform/$$scalaVer/sourceJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-sources.jar; \
			cp out/etl4s/$$platform/$$scalaVer/docJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-javadoc.jar; \
			for f in $$dir/*; do \
				md5sum $$f | cut -d' ' -f1 > $$f.md5; \
				sha1sum $$f | cut -d' ' -f1 > $$f.sha1; \
				gpg --batch --yes -ab -u $(GPG_KEY) $$f; \
			done; \
			echo "Packaged $$artifactId"; \
		done; \
	done
	@cd $(BUNDLE_DIR) && zip -r etl4s-$(VERSION)-bundle.zip xyz
	@rm -rf $(BUNDLE_DIR)/xyz
	@echo "\nBundle ready: $(BUNDLE_DIR)/etl4s-$(VERSION)-bundle.zip"

docs:
	@echo "Building documentation..."
	mkdocs build

publish-doc: docs
	@echo "Publishing documentation to etl4s.dev..."
	rsync -avz --delete site/ root@nargothrond.xyz:/var/www/etl4s.dev/
	@echo "Documentation published to https://etl4s.dev"
