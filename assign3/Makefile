SRC_DIR = src
JC = javac

SOURCES := $(wildcard $(SRC_DIR)/*.java)

all:
	$(JC) $(SOURCES)

clean:
	rm -f $(SRC_DIR)/*.class

.PHONY: all clean
