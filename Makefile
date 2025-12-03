.PHONY: test test2 test3 clean

test: test2 test3

test2:
	@echo "Running Scala 2 tests..."
	cd scala-2 && scala-cli test . ../tests

test3:
	@echo "Running Scala 3 tests..."
	cd scala-3 && scala-cli test . ../tests

clean:
	rm -rf scala-2/.scala-build scala-2/.bsp
	rm -rf scala-3/.scala-build scala-3/.bsp
