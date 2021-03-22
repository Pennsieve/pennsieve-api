MJML_SRCS = $(wildcard mjml/*.mjml)
HTML_TARGETS = $(patsubst mjml/%.mjml, html/%.html, $(MJML_SRCS))

all: $(HTML_TARGETS)

echo:
	echo $(HTML_TARGETS)

html/%.html: mjml/%.mjml
	./node_modules/mjml/bin/mjml $< -o html/$*.html

install:
	npm install

clean:
	rm html/*
