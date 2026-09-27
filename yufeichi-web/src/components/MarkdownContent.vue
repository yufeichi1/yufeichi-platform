<script setup lang="ts">
import { computed } from "vue";
import MarkdownIt from "markdown-it";
import DOMPurify from "dompurify";
import { imageUrl, linkUrl, externalUrl } from "@/utils/urls";
const props = defineProps<{ content: string }>();
const md = new MarkdownIt({ html: false, linkify: false, typographer: false });
md.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  const token = tokens[index]!;
  const href = linkUrl(String(token.attrGet("href") || ""));
  if (href) {
    token.attrSet("href", href);
    if (externalUrl(href)) {
      token.attrSet("target", "_blank");
      token.attrSet("rel", "noopener noreferrer");
    }
  } else {
    const attribute = token.attrIndex("href");
    if (attribute >= 0) token.attrs?.splice(attribute, 1);
  }
  return self.renderToken(tokens, index, options);
};
md.renderer.rules.image = (tokens, index, options, _env, self) => {
  const token = tokens[index]!,
    src = imageUrl(String(token.attrGet("src") || ""));
  if (!src)
    return (
      "<span>" +
      md.utils.escapeHtml(token.content || "图片地址不受支持") +
      "</span>"
    );
  token.attrSet("src", src);
  token.attrSet("alt", token.content);
  token.attrSet("loading", "lazy");
  token.attrSet("referrerpolicy", "no-referrer");
  return self.renderToken(tokens, index, options);
};
const html = computed(() =>
  DOMPurify.sanitize(md.render(props.content), {
    ALLOWED_TAGS: [
      "p",
      "br",
      "hr",
      "h1",
      "h2",
      "h3",
      "h4",
      "h5",
      "h6",
      "blockquote",
      "pre",
      "code",
      "em",
      "strong",
      "s",
      "ul",
      "ol",
      "li",
      "a",
      "img",
      "table",
      "thead",
      "tbody",
      "tr",
      "th",
      "td",
      "span",
    ],
    ALLOWED_ATTR: [
      "href",
      "src",
      "alt",
      "title",
      "class",
      "target",
      "rel",
      "loading",
      "referrerpolicy",
      "start",
    ],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  }),
);
</script>
<template><div class="markdown-content" v-html="html" /></template>
