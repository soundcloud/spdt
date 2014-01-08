.PHONY: all target build clean assembly compile test sbt

PREFIX_PATH = $$(pwd)/target
CONF_PATH   = $$(pwd)/project
IVY_PATH    = $(CONF_PATH)/ivy2
BOOT_PATH   = $(CONF_PATH)/boot
GEM_PATH    = $(PREFIX_PATH)/gems
LIB_PATH    = $(PREFIX_PATH)/lib
ENV         = TMPDIR=$(PREFIX_PATH) LD_LIBRARY_PATH=$(LIB_PATH) DYLD_LIBRARY_PATH=$(LIB_PATH)
SBT         = env $(ENV) ./sbt

all: build

build: target test assembly

clean:
	rm -rf $(PREFIX_PATH)
	rm -rf .sbt
	cd project && rm -rf project target boot ivy2
	cd compute && rm -rf target
	cd serve && rm -rf target

target:
	mkdir -p $(PREFIX_PATH) $(GEM_PATH) $(LIB_PATH) $(IVY_PATH) $(BOOT_PATH)

assembly: target
	$(SBT) assembly

compile:
	$(SBT) compile

test:
	$(SBT) test

sbt: target
	$(SBT)

