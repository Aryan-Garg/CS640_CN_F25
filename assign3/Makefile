SRC_DIR = src
JC = javac

SOURCES := $(wildcard $(SRC_DIR)/*.java)
CLASSES := $(SOURCES:.java=.class)

all: $(CLASSES)

$(SRC_DIR)/%.class: $(SRC_DIR)/%.java
	$(JC) $<

clean:
	rm -f $(SRC_DIR)/*.class

.PHONY: all clean
