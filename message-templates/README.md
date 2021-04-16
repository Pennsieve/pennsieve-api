# Data Management Email Templates

## Requirements

- [MJML](https://mjml.io/)

## Usage

Emails are stored as `.mjml` files in the `mjml` directory, and their outputs are in the `html` directory as `.html` files.

Run `make` to generate the `.html` files from the MJML templates. `.html` output files *must* be commited to the repository to be picked up by the Scala project.

The `CompileMessageTemplates` SBT plugin reads the `.html` files and generates Scala function definitions

### Cognito Templates - Authentication

Since authentication has been moved to Cognito for this project, the emails for "reset password" and "new account creation" are formatted specifically (and perhaps jankily) for a separate repository containing a Terraform project to deploy Pennsieve to AWS. This procedure was developed to be run locally as a manual step and outside of build proccesses, though can be formatted for whatever.

1. within this folder, run `npm install`
2. ensure the `infrastructure` and/or the sub repo `authentication-service` are locally accessible
3. run `PENNSIEVE_AUTHENTICATION_REPO_DIR="../../path/to/auth/dir" make auth_cognito_compile` - `PENNSIEVE_AUTHENTICATION_REPO_DIR` can be set as an environment variable, and within the Makefile defaults to `../../authentication-service/terraform/emails`
4. check your terraform repo dir and ensure git recognizes the changes you made to the template
 
## Development

### Online

Don't want to install anything? Use the free online editor!

<p align="center">
  <a href="http://mjml.io/try-it-live" target="_blank"><img src="https://cloud.githubusercontent.com/assets/6558790/12195421/58a40618-b5f7-11e5-9ed3-80463874ab14.png" alt="try it live" width="75%"></a>
</p>
<br>

### Command line interface

> Compiles the file and outputs the HTML generated in `output.html`

```bash
mjml input.mjml -o output.html
```

You can pass optional `arguments` to the CLI and combine them.

argument | description | default value
---------|--------|--------------
`mjml -m [input]` | Migrates a v3 MJML file to the v4 syntax | NA
`mjml [input] -o [output]` | Writes the output to [output] | NA
`mjml [input] -s` | Writes the output to `stdout` | NA
`mjml -w [input]` | Watches the changes made to `[input]` (file or folder) | NA
`mjml [input] --config.beautify` | Beautifies the output (`true` or `false`) | true
`mjml [input] --config.minify` | Minifies the output (`true` or `false`) | false

See [mjml-cli documentation](https://github.com/mjmlio/mjml/blob/master/packages/mjml-cli/README.md) for more information about config options.

## Future considerations

Use a more DX friendly framework such as [Maizzle](https://maizzle.com/)
