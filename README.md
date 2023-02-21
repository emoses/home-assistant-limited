# Home Assistant Limited

A Monster-in-the-middle proxy for Home Assisant that provides user-based access control for your HA installation

## Installation

Download from https://github.com/ha-proxy/main

You'll need

 * A JRE installed
 * Clojure and the `clj` CLI tool installed
 * An [auth0](https://auth0.com) account for authenication.
 * A running [Home Assistant](https://home-assistant.io/) installation with a service user
 * A config.yaml (see below)
 * A reverse proxy that terminates SSL if you're going to use this outside of localhost.  Currently Home Assistant Limited doesn't serve HTTPS itself, although it could be made to. Auth0 will not (and should not!) redirect to a http URL for authentication.  I'm using Cloudflared on my local hosting setup, but you can use `nginx` or whatever other setup you'd like.


## Usage

There are a number of required paramters that are needed to run the server successfully.  These may be set via environment variables, or a map in a `.lein-env` file in the root of the project.  Sample `.lein-env`:

```edn
{:api-key            "[the api key for your service user in Home Assistant]"
 :auth0-domain       "[some domain].us.auth0.com"
 :auth0-clientid     "[auth0 client id]"
 :auth0-clientsecret "[auth0 client secret]"
 :proxy-target       "your.homeassistant.example"
 :proxy-target-port  443                             ;optional, default 443
 :proxy-target-https true                            ;optional, default true
 :server-name        "https://server.name.example"   ;defaults to "http://localhost:port"
 :port               8080}
```
These are parsed via `environ` and may be set using env vars in `SCREAMING_SNAKE_CASE` (e.g. `API_KEY`, `AUTH0_DOMAIN`, etc)


Run the project directly,

    $ clj -Mrun

Run the project's tests

    $ clj -T:build test

Build an uberjar

    $ clj -T:build uber


Run that uberjar:

    $ java -jar target/server.jar

Build a multiarch docker image and push it to a repository (this requires docker to be installed; on a mac you need to enable experimental features on Docker Desktop).  To set repo, tag, and architectures, see the vars in `build.clj`:

    $ clj -T:build docker

## Configuration/access control

Configuration is in `resources/config.yaml`. There's an example config file at `resources/config.yaml.example`.

```yaml
users:
  "auth0|000000000000000000000000": # this is the sub ID from an auth0 ID token, it identifies the user
    landing: "/lovelace-name" # optional, if the dashboard your limited user is using isn't the default, you'll need to set this here.
    entities: #currently only supports light, swtich, and script
      - light.bedroom
      - script.do_stuff
    readonly_entities: # These are currently treated the same as entities
      - binary_sensor.door
      - sensor.temperature
    sidebar:
      - lovelace-name
      - lovelace # include this to include the default Overview
```

## How it works

The client connects to the proxy and is directed to an auth0 oauth flow.  After auth, all requests to the frontend are proxied directly to Home Assistant instance pointed to by `PROXY_TARGET`.  The proxy intercepts the main html page and injects a small script that sets `window.externalapp`, which Home Assistant's frontend usually uses when it's embedded in a native app to let the app manage authentication.

Lovelace, Home Assistant's frontend, does almost all of its data retrieval via a websocket connection to `/api/websocket`.  The proxy intercepts all the traffic on this websocket.  It injects an authentication message using the service user api-token provided to it, and then it proceeds to filter out any state data from the backend that the configured user isn't supposed to get, and also filters out any service calls that the frontend makes that aren't allowed.

There is no UI for this, but the user logged in to auth0 can be logged out by navigating to `/auth/logout`

All requests to `/api/*` other than the websocket endpoint will 404.

## TODO

-  [ ] More entity domains in the configuration
-  [ ] Document Auth0 setup
-  [ ] Support more auth backends.  Any oauth backend should be straightforward
-  [ ] Connecting via the iOS and android apps. This is not straightfoward.
-  [ ] `readonly_entities` should do what it says on the box (i.e. you should be able to read their state but not execute service calls with them as a target)
-  [ ] Rename everything from `ha-proxy`. Yes I'm aware that `HAProxy` is a thing.  But hey, this proxies HA.
-  [ ] Support serving HTTPS

## License

Copyright © 2022 Evan Moses

Distributed under the Apache 2.0 License
