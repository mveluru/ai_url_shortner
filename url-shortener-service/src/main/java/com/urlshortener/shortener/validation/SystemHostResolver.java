package com.urlshortener.shortener.validation;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

@Component
class SystemHostResolver implements HostResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
