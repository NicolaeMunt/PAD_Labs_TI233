package md.utm.demo.game.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.fasterxml.jackson.annotation.JsonRootName;

import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * One page of games. JSON: {"content":[...],"page":{...}}.
 * XML:  <games><content><game/>...</content><page>...</page></games>
 * (the XML annotations are ignored by the JSON mapper).
 */
@JsonRootName("games")
public record GamePage(
        @JacksonXmlElementWrapper(localName = "content")
        @JacksonXmlProperty(localName = "game")
        List<GameResponse> content,
        PageInfo page
) {
    public record PageInfo(int size, int number, long totalElements, int totalPages) { }

    public static GamePage from(Page<GameResponse> p) {
        return new GamePage(p.getContent(),
                new PageInfo(p.getSize(), p.getNumber(), p.getTotalElements(), p.getTotalPages()));
    }
}
