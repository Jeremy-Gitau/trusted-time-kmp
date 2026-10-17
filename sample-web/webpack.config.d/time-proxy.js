config.devServer = config.devServer || {};
config.devServer.proxy = [
    {
        context: ["/time"],
        target: "https://www.apple.com",
        changeOrigin: true,
        pathRewrite: { "^/time": "/" },
    },
];
