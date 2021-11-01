.PHONY: init, repl, test

init:
	npm ci

repl:
	clj -M:nrepl:ui:test -m nrepl.cmdline --middleware [cider.nrepl/cider-middleware,shadow.cljs.devtools.server.nrepl/middleware]

test:
	clj -A:test -m kaocha.runner
