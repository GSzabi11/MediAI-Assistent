package bme.mediai.document.persistence;

import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Megváltoztathatatlan: a darabolás a kinyert szöveg pozícióira épül.
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {

    public static final int EMBEDDING_DIMENSION = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long documentId;

    private String text;

    private Integer charStart;

    private Integer charEnd;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    private float[] embedding;

    private String embeddingModel;

    protected DocumentChunk() {
    }

    public DocumentChunk(Long documentId, String text, Integer charStart, Integer charEnd,
            float[] embedding, String embeddingModel) {
        this.documentId = documentId;
        this.text = text;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getText() {
        return text;
    }

    public Integer getCharStart() {
        return charStart;
    }

    public Integer getCharEnd() {
        return charEnd;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}
