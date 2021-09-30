init:
	npm ci

repl:
	clj -M:nrepl:ui -m nrepl.cmdline --middleware [cider.nrepl/cider-middleware,shadow.cljs.devtools.server.nrepl/middleware]
