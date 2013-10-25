# Schnorres – Moustache for aleph

Schnorres is a moustache for [aleph](http://github.com/ztellman/aleph).
It works exactly as moustache, but with aleph handlers instead of ring
handlers.

# Moustache

    (app ["hi"] {:get "Hello World!"})

Moustache is a micro web framework/internal DSL to wire Ring handlers and
middlewares.

## How micro is it?

Well, there's only one macro you need to know: `app`.

Every other public var is public only because `app` needs it in its expansion.

## Syntax

See [syntax.html](http://moustache.cgrand.net/syntax.html).

## Walkthrough

<http://gist.github.com/109955>

## The <code>app</code> macro

A `(app ...)` form returns a Ring application (handler).

There's currently four usages of @app@:
 * to wrap a Ring handler,
 * to define routes,
 * to dispatch on HTTP methods
 * and to render plain text.

### Wrapping an existing Ring handler

    (app my-handler) ; identity, returns my-handler

You can simply wrap a handler into middlewares:

    (app
      middleware1
      (middleware2 arg)
      my-handler)
    ; equivalent to (-> my-handler (middleware2 arg) middleware1)
    ; ie (middleware1 (middleware2 my-handler arg))

Note that *every usage of `app` supports middleware-wrapping*.

### Routes

#### Basics

With Moustache you don't write routes as encoded uri (eg
`"/Thank%20you%20Mario/But%20our%20princess%20is%20in%20another%20castle"`),
you write vectors of decoded segments (eg `["Thank you Mario" "But our princess is in another castle"]`).

    (app ["foo"] my-handler) ; will route requests to "/foo" to my-handler
    (app ["foo" ""] my-handler) ; will route requests to "/foo/" to my-handler
    (app ["foo" "bar"] my-handler) ; will route requests to "/foo/bar" to my-handler
    (app ["foo" &] my-handler) ; will route requests to "/foo", "/foo/", "/foo/bar" and "/foo/bar/baz/" to my-handler (and will chop "/foo" off from the uri)
    (app ["foo" name] my-handler) ; will route requests to "/foo/", "/foo/bar" to my-handler and bind @name@ (a local) to the matched segment (eg "" or "bar")
    (app ["foo" x & xs] my-handler) ; "/foo/bar/baz/bloom" will bind x to bar and xs to ["baz" "bloom"]

You can catch all URIs with the route `[&]`. If you don't provide a
handler for `[&]` and there's no handler for a request Moustache
sends a 404 (not found) response.

#### Route validation/destructuring

    (defn integer
      "returns nil if s does not represent an integer
      [s]
      (try
        (Integer/parseInt s)
        (catch Exception e)))
    
    (app ["order" [id integer]] my-handler) ; for "/order/134" id will be bound to 134 (not "134"), this route will not match "/order/abc".
    
    (app ["agenda" [[_ year month day] #"(\d{4})-(\d{2})-(\d{2})"]]
      {:get [month "-" day "-" year " agenda"]})

#### Fall through

The routes are tried in order until one route matches the request uri *and* the
associated handler does not return nil.

That's why:

   (app
     ["foo" &] (app ["bar"] handler1)
     ["foo" "baz"] handler2)

returns a 404 for /foo/baz: the nested `app` form returns a 404 for /baz and
this 404 bubbles up.

You can prevent such behavior by writing:

   (app
     ["foo" &] (app
                 ["bar"] handler1
                 [&] pass)
     ["foo" "baz"] handler2)

### Method dispatch

    (app
      :get handler-for-get
      :post handler-for-post)

You can add a catch-all using the :any keyword.

If you don't specify a handler for :any, Moustache sends a 405 response (method
not allowed).

### Shorthands

When the right-hand form of a route or of a method dispatch is a `(app ...)`
form, you can write the form as a vector: `(app ["foo" &] (app ["bar"] handler))`
can be shortened to `(app ["foo" &] [["bar"] handler])`.

Besides when the right-hand form is a method dispatch without middlewares you
can write the form as a map:  `(app ["foo"] (app :get handler))` can
be shortened to `(app ["foo"] {:get handler})`.

