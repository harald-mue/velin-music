package admin

import (
	"net/url"
	"testing"
)

func TestRelativeAdminLocationPreservesExternalPrefix(t *testing.T) {
	tests := []struct {
		name        string
		requestPath string
		targetPath  string
		want        string
	}{
		{name: "index to setup", requestPath: "/admin/", targetPath: "/admin/setup", want: "setup"},
		{name: "setup to index", requestPath: "/admin/setup", targetPath: "/admin/", want: "./"},
		{name: "root post to roots", requestPath: "/admin/roots", targetPath: "/admin/roots", want: "roots"},
		{name: "nested root action", requestPath: "/admin/roots/root-1/remove", targetPath: "/admin/roots", want: "../../roots"},
		{name: "scan detail to roots", requestPath: "/admin/scans/scan-1", targetPath: "/admin/roots", want: "../roots"},
		{name: "nested page to login", requestPath: "/admin/scans/scan-1", targetPath: "/admin/login", want: "../login"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := relativeAdminLocation(test.requestPath, test.targetPath); got != test.want {
				t.Fatalf("relativeAdminLocation(%q, %q) = %q, want %q", test.requestPath, test.targetPath, got, test.want)
			}
		})
	}
}

func TestRelativeAdminLocationResolvesBelowExternalPrefix(t *testing.T) {
	requestURL, err := url.Parse("https://home.example/velin/admin/roots/root-1/remove")
	if err != nil {
		t.Fatalf("parse request URL: %v", err)
	}
	location, err := url.Parse(relativeAdminLocation("/admin/roots/root-1/remove", "/admin/roots"))
	if err != nil {
		t.Fatalf("parse relative location: %v", err)
	}
	if got := requestURL.ResolveReference(location).String(); got != "https://home.example/velin/admin/roots" {
		t.Fatalf("resolved redirect = %q", got)
	}
}
