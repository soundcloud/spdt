.PHONY: all target build clean assembly compile test sbt

all: build

build: target test assembly

clean:
	sbt clean

assembly: target
	sbt assembly

compile:
	sbt compile

test:
	sbt test

sbt: target
	sbt

