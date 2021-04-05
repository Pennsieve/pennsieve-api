MJML_SRCS = $(wildcard mjml/*.mjml)
HTML_TARGETS = $(patsubst mjml/%.mjml, html/%.html, $(MJML_SRCS))
PENNSIEVE_AUTHENTICATION_REPO_DIR ?= ../../authentication-service/terraform/emails
PENNSIEVE_DOMAIN ?= app\.pennsieve\.net

all: $(HTML_TARGETS)

echo:
	echo $(HTML_TARGETS)

html/%.html: mjml/%.mjml
	./node_modules/mjml/bin/mjml $< -o html/$*.html

install:
	npm install

clean:
	rm html/*

auth_cognito_compile:
	npm run auth-cognito-compile --dir=${PENNSIEVE_AUTHENTICATION_REPO_DIR}
	sed -i '' -e 's/$${emailAddress}/\{username\}/g' ${PENNSIEVE_AUTHENTICATION_REPO_DIR}/new-account-creation.template.html

	sed -i '' -e 's/$${emailAddress}/\{username\}/g' ${PENNSIEVE_AUTHENTICATION_REPO_DIR}/password-reset.template.html

