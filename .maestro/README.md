# Maestro

Maestro is a framework that we are using to test navigation across the application.
To setup, please refer at [https://maestro.mobile.dev](https://maestro.mobile.dev)

<!--- TOC -->

* [Run test](#run-test)
  * [Output](#output)
* [Write test](#write-test)
* [CI](#ci)
* [iOS](#ios)
* [Future](#future)

<!--- END -->

## Run test

From root dir of the project

*Note: SecureChat disables account creation by default, so the Maestro suite needs an existing
`chat.securechat.com.au` test account and a room that account has joined. The test sends messages
to the configured room.*

```shell
maestro test \
    -e MAESTRO_APP_ID=com.securechat.app.debug \
    -e MAESTRO_USERNAME=maestrosecurechat \
    -e MAESTRO_PASSWORD=123 \
    -e MAESTRO_RECOVERY_KEY=ABC \
    -e MAESTRO_ROOM_NAME="MyRoom" \
    -e MAESTRO_INVITEE1_MXID=@maestrosecurechat2:chat.securechat.com.au \
    -e MAESTRO_INVITEE2_MXID=@maestrosecurechat3:chat.securechat.com.au \
    .maestro/allTests.yaml
```

### Output

Test result will be printed on the console, and screenshots will be generated at `./build/maestro`

## Write test

Tests are yaml files. Generally each yaml file should leave the app in the same screen than at the beginning.

Start SecureChat and run this command to help write a test.

```shell
maestro studio
```

Note that sometimes, this prevent running the test. So kill the `maestro studio` process to be able to run the test again.

Also, if updating the application code, do not forget to deploy again the application before running the maestro tests.

## CI

CI runs Maestro in a local Android emulator through `.github/workflows/maestro-local.yml`.
`MATRIX_MAESTRO_ACCOUNT_PASSWORD` and `MATRIX_MAESTRO_ACCOUNT_RECOVERY_KEY` must belong to the
SecureChat-owned test account configured in that workflow. The account must have access to `MyRoom`
and the two configured invitee accounts.

## iOS

Need to install `idb-companion` first

```shell
brew install idb-companion
```

Also:
https://github.com/mobile-dev-inc/maestro/issues/146
https://github.com/mobile-dev-inc/maestro/issues/107
So you have to change your input keyboard to QWERTY for it to work properly.

## Future

- Run on an iOS client by passing a platform parameter and using conditional commands where needed.
- Run selected tests across iOS and Android devices to cover room invites, verification, and calls.
